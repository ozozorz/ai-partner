package io.github.ozozorz.aipartner.world;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/**
 * 发给解析器和实验日志的最小服务器权威状态摘要。
 */
public record WorldStateSummary(
        String dimension,
        String partnerPosition,
        String playerPosition,
        double distanceToPlayer,
        float partnerHealth,
        float partnerMaxHealth,
        boolean ownerOnline,
        String mode,
        String contractStatus,
        String jobType,
        boolean navigationDone
) {
    /**
     * 从当前服务器状态构建不包含整个区块或任意 NBT 的摘要。
     */
    public static WorldStateSummary capture(AiPartnerEntity partner, ServerPlayer player) {
        boolean sameDimension = partner.level() == player.level();
        return new WorldStateSummary(
                partner.level().dimension().identifier().toString(),
                position(partner.getX(), partner.getY(), partner.getZ()),
                position(player.getX(), player.getY(), player.getZ()),
                sameDimension ? Math.sqrt(partner.distanceToSqr(player)) : -1.0,
                partner.getHealth(),
                partner.getMaxHealth(),
                partner.getOwner() != null,
                partner.getMode().name(),
                partner.getCurrentContract().map(contract -> contract.status().name()).orElse("NONE"),
                partner.getCurrentContract().map(contract -> contract.job().type().name()).orElse("NONE"),
                partner.getNavigation().isDone()
        );
    }

    private static String position(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", x, y, z);
    }
}

