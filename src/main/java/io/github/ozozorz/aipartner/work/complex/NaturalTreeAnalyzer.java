package io.github.ozozorz.aipartner.work.complex;

import io.github.ozozorz.aipartner.life.ActivityLocation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 对活动区内的原版自然原木执行有界连通分析，并拒绝含建筑木件或方块实体的结构。
 */
public final class NaturalTreeAnalyzer {
    private static final int LEAF_SCAN_RADIUS = 2;

    private NaturalTreeAnalyzer() {
    }

    public static Optional<NaturalTreePlan> analyze(
            ServerLevel level,
            ActivityLocation boundary,
            BlockPos candidateRoot
    ) {
        BlockState rootState = level.getBlockState(candidateRoot);
        if (!rootState.is(BlockTags.OVERWORLD_NATURAL_LOGS)
                || !level.getBlockState(candidateRoot.below()).is(BlockTags.DIRT)) {
            return Optional.empty();
        }

        Set<BlockPos> logs = collectConnectedLogs(level, boundary, candidateRoot);
        if (logs.isEmpty() || logs.size() > NaturalTreePolicy.MAX_LOGS) {
            return Optional.empty();
        }

        int minY = logs.stream().mapToInt(BlockPos::getY).min().orElse(candidateRoot.getY());
        int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElse(candidateRoot.getY());
        int rootCount = (int) logs.stream()
                .filter(pos -> pos.getY() == minY && level.getBlockState(pos.below()).is(BlockTags.DIRT))
                .count();
        Set<BlockPos> leaves = collectLeaves(level, logs);
        int topLeafCount = (int) leaves.stream().filter(pos -> pos.getY() >= maxY - 1).count();
        StructureFlags flags = inspectBoundingStructure(level, logs);
        NaturalTreePolicy.Summary summary = new NaturalTreePolicy.Summary(
                logs.size(),
                leaves.size(),
                topLeafCount,
                maxY - minY + 1,
                rootCount,
                flags.hasBlockEntity(),
                flags.hasPlayerWoodComponent()
        );
        if (!NaturalTreePolicy.accepts(summary)) {
            return Optional.empty();
        }

        List<BlockPos> ordered = new ArrayList<>(logs);
        ordered.sort(Comparator
                .<BlockPos>comparingInt(BlockPos::getY)
                .reversed()
                .thenComparingDouble(pos -> pos.distSqr(candidateRoot)));
        return Optional.of(new NaturalTreePlan(ordered));
    }

    private static Set<BlockPos> collectConnectedLogs(
            ServerLevel level,
            ActivityLocation boundary,
            BlockPos start
    ) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (visited.size() > NaturalTreePolicy.MAX_LOGS
                    || !boundary.contains(level, current)
                    || !isLoaded(level, current)
                    || !level.getBlockState(current).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
                return Set.of();
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (level.getBlockState(neighbor).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
                    if (!boundary.contains(level, neighbor)) {
                        return Set.of();
                    }
                    if (!visited.contains(neighbor)) {
                        queue.add(neighbor.immutable());
                    }
                }
            }
        }
        return visited;
    }

    private static Set<BlockPos> collectLeaves(ServerLevel level, Set<BlockPos> logs) {
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (BlockPos candidate : BlockPos.betweenClosed(
                    log.offset(-LEAF_SCAN_RADIUS, -LEAF_SCAN_RADIUS, -LEAF_SCAN_RADIUS),
                    log.offset(LEAF_SCAN_RADIUS, LEAF_SCAN_RADIUS, LEAF_SCAN_RADIUS)
            )) {
                if (level.getBlockState(candidate).is(BlockTags.LEAVES)) {
                    leaves.add(candidate.immutable());
                }
            }
        }
        return leaves;
    }

    private static StructureFlags inspectBoundingStructure(ServerLevel level, Set<BlockPos> logs) {
        int minX = logs.stream().mapToInt(BlockPos::getX).min().orElse(0) - 1;
        int minY = logs.stream().mapToInt(BlockPos::getY).min().orElse(0) - 1;
        int minZ = logs.stream().mapToInt(BlockPos::getZ).min().orElse(0) - 1;
        int maxX = logs.stream().mapToInt(BlockPos::getX).max().orElse(0) + 1;
        int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElse(0) + 1;
        int maxZ = logs.stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1;
        boolean hasBlockEntity = false;
        boolean hasPlayerWood = false;
        for (BlockPos position : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState state = level.getBlockState(position);
            hasBlockEntity |= level.getBlockEntity(position) != null;
            hasPlayerWood |= isPlayerWoodComponent(state);
            if (hasBlockEntity && hasPlayerWood) {
                break;
            }
        }
        return new StructureFlags(hasBlockEntity, hasPlayerWood);
    }

    private static boolean isPlayerWoodComponent(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOODEN_BUTTONS)
                || state.is(BlockTags.WOODEN_PRESSURE_PLATES);
    }

    private static boolean isLoaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())
        ) != null;
    }

    private record StructureFlags(boolean hasBlockEntity, boolean hasPlayerWoodComponent) {
    }
}
