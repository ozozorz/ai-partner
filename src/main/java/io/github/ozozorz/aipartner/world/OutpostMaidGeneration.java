package io.github.ozozorz.aipartner.world;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.registry.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 在每个新生成的掠夺者前哨站旁追加一座女仆笼子。
 *
 * <p>仅监听区块首次世界生成，并且只处理结构起始区块，因此不会随怪物刷新重复生成，
 * 也不会替换前哨站原有的悦灵或铁傀儡笼。</p>
 */
public final class OutpostMaidGeneration {
    private static final int CAGE_RADIUS = 2;
    private static final int CAGE_HEIGHT = 4;
    private static final int CAGE_CLEARANCE = 1;
    private static final int SEARCH_MARGIN = 12;
    private static final String OUTPOST_MAID_TAG = "ai_partner_outpost_maid";
    private static final Map<ServerLevel, ArrayDeque<PendingOutpost>> PENDING_OUTPOSTS = new WeakHashMap<>();

    private OutpostMaidGeneration() {
    }

    @SuppressWarnings("deprecation")
    public static void register() {
        ServerChunkEvents.CHUNK_GENERATE.register(OutpostMaidGeneration::onChunkGenerated);
        ServerTickEvents.END_LEVEL_TICK.register(OutpostMaidGeneration::onEndLevelTick);
    }

    private static void onChunkGenerated(ServerLevel level, LevelChunk chunk) {
        Structure outpost = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(BuiltinStructures.PILLAGER_OUTPOST);
        if (outpost == null) {
            return;
        }
        StructureStart start = chunk.getStartForStructure(outpost);
        if (start == null || !start.isValid() || !start.getChunkPos().equals(chunk.getPos())) {
            return;
        }

        ArrayDeque<PendingOutpost> pending = PENDING_OUTPOSTS.computeIfAbsent(level, ignored -> new ArrayDeque<>());
        if (pending.stream().anyMatch(entry -> entry.startChunk().equals(chunk.getPos()))) {
            return;
        }
        pending.addLast(new PendingOutpost(
                chunk.getPos(),
                copyBox(start.getBoundingBox()),
                start.getPieces().stream()
                        .map(StructurePiece::getBoundingBox)
                        .map(OutpostMaidGeneration::copyBox)
                        .toList()
        ));
    }

    /**
     * 在区块生成 future 完成后的世界 tick 中落地笼子，避免从 CHUNK_GENERATE
     * 回调里再次请求同一区块而造成服务器线程自锁。
     */
    private static void onEndLevelTick(ServerLevel level) {
        ArrayDeque<PendingOutpost> pending = PENDING_OUTPOSTS.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        int entriesThisTick = pending.size();
        for (int index = 0; index < entriesThisTick; index++) {
            PendingOutpost outpost = pending.removeFirst();
            if (!isSearchAreaLoaded(level, outpost.structureBounds())) {
                pending.addLast(outpost);
                continue;
            }

            BlockPos center = findCageCenter(level, outpost);
            if (center == null) {
                AiPartnerMod.LOGGER.warn(
                        "Could not find grounded cage site for pillager outpost at {}",
                        outpost.startChunk()
                );
                continue;
            }

            buildCage(level, center);
            if (!spawnMaid(level, center.above())) {
                pending.addLast(outpost);
            }
        }

        if (pending.isEmpty()) {
            PENDING_OUTPOSTS.remove(level);
        }
    }

    private static boolean isSearchAreaLoaded(ServerLevel level, BoundingBox bounds) {
        int minimumChunkX = SectionPos.blockToSectionCoord(bounds.minX() - SEARCH_MARGIN);
        int maximumChunkX = SectionPos.blockToSectionCoord(bounds.maxX() + SEARCH_MARGIN);
        int minimumChunkZ = SectionPos.blockToSectionCoord(bounds.minZ() - SEARCH_MARGIN);
        int maximumChunkZ = SectionPos.blockToSectionCoord(bounds.maxZ() + SEARCH_MARGIN);
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 优先选择平整且每格都有坚实地基的位置；崎岖地形下会放宽坡差并补齐地基，
     * 但始终拒绝水面、空气地基以及与任一原版结构部件相交的位置。
     */
    private static BlockPos findCageCenter(ServerLevel level, PendingOutpost outpost) {
        BlockPos center = findCageCenter(level, outpost, 2, true);
        if (center != null) {
            return center;
        }
        center = findCageCenter(level, outpost, 4, true);
        return center != null ? center : findCageCenter(level, outpost, 12, false);
    }

    private static BlockPos findCageCenter(
            ServerLevel level,
            PendingOutpost outpost,
            int maximumTerrainStep,
            boolean requireSturdyGround
    ) {
        BoundingBox bounds = outpost.structureBounds();
        BlockPos structureCenter = bounds.getCenter();
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int worldX = bounds.minX() - SEARCH_MARGIN; worldX <= bounds.maxX() + SEARCH_MARGIN; worldX++) {
            for (int worldZ = bounds.minZ() - SEARCH_MARGIN; worldZ <= bounds.maxZ() + SEARCH_MARGIN; worldZ++) {
                int minimumSurface = Integer.MAX_VALUE;
                int maximumSurface = Integer.MIN_VALUE;
                boolean supported = true;
                surface:
                for (int dx = -CAGE_RADIUS; dx <= CAGE_RADIUS; dx++) {
                    for (int dz = -CAGE_RADIUS; dz <= CAGE_RADIUS; dz++) {
                        int surface = level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                worldX + dx,
                                worldZ + dz
                        );
                        BlockPos ground = new BlockPos(worldX + dx, surface - 1, worldZ + dz);
                        BlockState groundState = level.getBlockState(ground);
                        boolean validGround = !groundState.isAir() && groundState.getFluidState().isEmpty();
                        if (requireSturdyGround) {
                            validGround = validGround && groundState.isFaceSturdy(level, ground, Direction.UP);
                        }
                        if (!validGround) {
                            supported = false;
                            break surface;
                        }
                        minimumSurface = Math.min(minimumSurface, surface);
                        maximumSurface = Math.max(maximumSurface, surface);
                    }
                }
                if (!supported || maximumSurface - minimumSurface > maximumTerrainStep) {
                    continue;
                }

                int floorY = maximumSurface;
                BoundingBox cageBox = new BoundingBox(
                        worldX - CAGE_RADIUS,
                        floorY - 1,
                        worldZ - CAGE_RADIUS,
                        worldX + CAGE_RADIUS,
                        floorY + CAGE_HEIGHT,
                        worldZ + CAGE_RADIUS
                ).inflatedBy(CAGE_CLEARANCE, 0, CAGE_CLEARANCE);
                boolean intersectsStructure = outpost.pieceBounds().stream().anyMatch(cageBox::intersects);
                if (intersectsStructure) {
                    continue;
                }

                BlockPos candidate = new BlockPos(worldX, floorY, worldZ);
                double score = candidate.distSqr(structureCenter)
                        + (maximumSurface - minimumSurface) * 256.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * 建造前哨站风格的封闭笼子，并从自然地表补齐到统一地板高度，
     * 保证崎岖地形上的笼子也不会悬空。
     */
    private static void buildCage(ServerLevel level, BlockPos center) {
        for (int dx = -CAGE_RADIUS; dx <= CAGE_RADIUS; dx++) {
            for (int dz = -CAGE_RADIUS; dz <= CAGE_RADIUS; dz++) {
                boolean perimeter = Math.abs(dx) == CAGE_RADIUS || Math.abs(dz) == CAGE_RADIUS;
                boolean corner = Math.abs(dx) == CAGE_RADIUS && Math.abs(dz) == CAGE_RADIUS;
                BlockPos floor = center.offset(dx, 0, dz);
                int surface = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        floor.getX(),
                        floor.getZ()
                );
                for (int supportY = surface; supportY <= center.getY(); supportY++) {
                    Block support = corner ? Blocks.DARK_OAK_LOG : Blocks.DARK_OAK_PLANKS;
                    level.setBlock(
                            new BlockPos(floor.getX(), supportY, floor.getZ()),
                            support.defaultBlockState(),
                            Block.UPDATE_ALL
                    );
                }
                for (int dy = 1; dy < CAGE_HEIGHT; dy++) {
                    Block block = corner ? Blocks.DARK_OAK_LOG : perimeter ? Blocks.IRON_BARS : Blocks.AIR;
                    level.setBlock(floor.above(dy), block.defaultBlockState(), Block.UPDATE_ALL);
                }
                level.setBlock(
                        floor.above(CAGE_HEIGHT),
                        Blocks.DARK_OAK_PLANKS.defaultBlockState(),
                        Block.UPDATE_ALL
                );
            }
        }
    }

    /**
     * 生成一只持久、未归属、待命的女仆；玩家仍需使用安全食物驯服。
     */
    private static boolean spawnMaid(ServerLevel level, BlockPos position) {
        AiPartnerEntity maid = ModEntities.AI_PARTNER.create(level, EntitySpawnReason.STRUCTURE);
        if (maid == null) {
            return false;
        }
        maid.snapTo(
                position.getX() + 0.5,
                position.getY(),
                position.getZ() + 0.5,
                maid.getRandom().nextFloat() * 360.0F,
                0.0F
        );
        maid.setMode(PartnerMode.STAY);
        maid.setPersistenceRequired();
        maid.addTag(OUTPOST_MAID_TAG);
        if (!level.addFreshEntity(maid)) {
            AiPartnerMod.LOGGER.warn("Failed to add outpost maid at {}", position);
            return false;
        }
        return true;
    }

    private static BoundingBox copyBox(BoundingBox box) {
        return new BoundingBox(
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ()
        );
    }

    /**
     * 保存等待世界 tick 落地的前哨站几何信息；不持有可变的 StructureStart。
     */
    private record PendingOutpost(
            ChunkPos startChunk,
            BoundingBox structureBounds,
            List<BoundingBox> pieceBounds
    ) {
    }
}
