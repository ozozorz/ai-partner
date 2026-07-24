package io.github.ozozorz.aipartner.service;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.registry.ModEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * 通过持久化主人索引提供女仆生成、选择、查找和生命周期登记。
 */
public final class PartnerService {
    private static final int[][] SPAWN_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private PartnerService() {
    }

    /**
     * 返回当前选中的已加载女仆；选择失效时回退到同维度最近实体。
     */
    public static Optional<AiPartnerEntity> findOwnedPartner(ServerPlayer player) {
        List<AiPartnerEntity> loaded = findOwnedPartners(player);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        MaidOwnershipState state = MaidOwnershipState.get(player.level().getServer());
        Optional<UUID> selected = state.selected(player.getUUID());
        if (selected.isPresent()) {
            Optional<AiPartnerEntity> match = loaded.stream()
                    .filter(partner -> partner.getUUID().equals(selected.get()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        AiPartnerEntity fallback = loaded.getFirst();
        state.select(player.getUUID(), fallback.getUUID());
        return Optional.of(fallback);
    }

    /**
     * 查找全部已加载女仆，并按同维度优先、距离从近到远排序。
     */
    public static List<AiPartnerEntity> findOwnedPartners(ServerPlayer player) {
        List<AiPartnerEntity> partners = new ArrayList<>();
        MinecraftServer server = player.level().getServer();
        MaidOwnershipState state = MaidOwnershipState.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AiPartnerEntity partner
                        && partner.isAlive()
                        && partner.isOwnedBy(player)) {
                    partners.add(partner);
                    state.register(player.getUUID(), partner.getUUID());
                }
            }
        }
        partners.sort(Comparator
                .comparing((AiPartnerEntity partner) -> partner.level() != player.level())
                .thenComparingDouble(player::distanceToSqr)
                .thenComparing(partner -> partner.getUUID().toString()));
        return List.copyOf(partners);
    }

    /**
     * 使用 UUID、UUID 前缀或唯一名称切换当前命令目标。
     */
    public static Optional<AiPartnerEntity> selectOwnedPartner(ServerPlayer player, String selector) {
        String normalized = selector.trim().toLowerCase(Locale.ROOT);
        List<AiPartnerEntity> matches = findOwnedPartners(player).stream()
                .filter(partner -> partner.getUUID().toString().toLowerCase(Locale.ROOT).startsWith(normalized)
                        || partner.getName().getString().toLowerCase(Locale.ROOT).equals(normalized))
                .toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }
        AiPartnerEntity selected = matches.getFirst();
        MaidOwnershipState.get(player.level().getServer()).select(player.getUUID(), selected.getUUID());
        return Optional.of(selected);
    }

    /**
     * 在玩家附近寻找安全位置，生成后立即绑定、登记并选中新女仆。
     */
    public static Optional<AiPartnerEntity> spawnPartner(ServerPlayer player) {
        ServerLevel level = player.level();
        AiPartnerEntity entity = ModEntities.AI_PARTNER.create(level, EntitySpawnReason.COMMAND);
        if (entity == null || !placeAtSafeNearbyPosition(level, player, entity)) {
            return Optional.empty();
        }
        entity.tame(player);
        entity.setPersistenceRequired();
        entity.setMode(PartnerMode.FOLLOW);
        String suffix = entity.getStringUUID().substring(0, 4).toUpperCase(Locale.ROOT);
        entity.setCustomName(Component.translatable("entity.ai-partner.ai_partner_named", suffix));
        entity.setCustomNameVisible(true);
        entity.initializeLifeAtSpawn();
        if (!level.addFreshEntity(entity)) {
            return Optional.empty();
        }
        MaidOwnershipState state = MaidOwnershipState.get(level.getServer());
        state.register(player.getUUID(), entity.getUUID());
        state.select(player.getUUID(), entity.getUUID());
        return Optional.of(entity);
    }

    public static void registerLoaded(AiPartnerEntity partner) {
        if (partner.level() instanceof ServerLevel level && partner.getOwnerReference() != null) {
            MaidOwnershipState.get(level.getServer()).register(
                    partner.getOwnerReference().getUUID(),
                    partner.getUUID()
            );
        }
    }

    public static void unregisterDestroyed(AiPartnerEntity partner) {
        if (partner.level() instanceof ServerLevel level && partner.getOwnerReference() != null) {
            MaidOwnershipState.get(level.getServer()).unregister(
                    partner.getOwnerReference().getUUID(),
                    partner.getUUID()
            );
        }
    }

    private static boolean placeAtSafeNearbyPosition(
            ServerLevel level,
            ServerPlayer player,
            AiPartnerEntity entity
    ) {
        BlockPos origin = player.blockPosition();
        for (int[] offset : SPAWN_OFFSETS) {
            for (int yOffset = 0; yOffset <= 2; yOffset++) {
                BlockPos candidate = origin.offset(offset[0], yOffset, offset[1]);
                BlockPos floor = candidate.below();
                entity.snapTo(
                        candidate.getX() + 0.5,
                        candidate.getY(),
                        candidate.getZ() + 0.5,
                        player.getYRot(),
                        0.0F
                );
                if (level.getWorldBorder().isWithinBounds(candidate)
                        && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                        && level.getFluidState(candidate).isEmpty()
                        && level.getFluidState(candidate.above()).isEmpty()
                        && level.noCollision(entity)) {
                    return true;
                }
            }
        }
        return false;
    }
}
