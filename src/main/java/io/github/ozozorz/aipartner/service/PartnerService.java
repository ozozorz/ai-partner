package io.github.ozozorz.aipartner.service;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModEntities;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * 提供单玩家单伙伴约束下的实体查找与生成操作。
 */
public final class PartnerService {
    private PartnerService() {
    }

    /**
     * 在全部已加载维度中查找玩家拥有的伙伴，并优先返回同维度最近实体。
     */
    public static Optional<AiPartnerEntity> findOwnedPartner(ServerPlayer player) {
        AiPartnerEntity bestPartner = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof AiPartnerEntity partner) || !partner.isAlive() || !partner.isOwnedBy(player)) {
                    continue;
                }
                double score = partner.level() == player.level()
                        ? player.distanceToSqr(partner)
                        : Double.MAX_VALUE;
                if (bestPartner == null || score < bestScore) {
                    bestPartner = partner;
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(bestPartner);
    }

    /**
     * 在玩家身旁生成并立即绑定一个持久化伙伴。
     */
    public static Optional<AiPartnerEntity> spawnPartner(ServerPlayer player) {
        ServerLevel level = player.level();
        AiPartnerEntity entity = ModEntities.AI_PARTNER.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            return Optional.empty();
        }
        entity.snapTo(player.getX() + 1.0, player.getY(), player.getZ(), player.getYRot(), 0.0F);
        entity.tame(player);
        entity.setPersistenceRequired();
        entity.setCustomName(Component.translatable("entity.ai-partner.ai_partner"));
        entity.setCustomNameVisible(true);
        return level.addFreshEntity(entity) ? Optional.of(entity) : Optional.empty();
    }
}
