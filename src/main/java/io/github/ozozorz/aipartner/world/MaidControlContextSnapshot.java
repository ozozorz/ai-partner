package io.github.ozozorz.aipartner.world;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bounded, server-authoritative context supplied to the high-level interpreter.
 * It deliberately omits arbitrary NBT, nearby entity dumps, and exact coordinates.
 */
public record MaidControlContextSnapshot(
        String maidName,
        String dimension,
        double distanceToOwner,
        float health,
        float maximumHealth,
        String behaviorMode,
        String workflowStatus,
        String contractStatus,
        String jobType,
        String workMode,
        String workExecutionState,
        String scheduleType,
        String scheduleActivity,
        String combatPolicy,
        boolean homeBound,
        int activityRadius,
        String inventorySummary,
        String previousInstruction,
        String previousClarificationQuestion
) {
    private static final int MAX_INVENTORY_SUMMARY_LENGTH = 2048;

    /** Captures the current maid state on the server thread. */
    public static MaidControlContextSnapshot capture(
            AiPartnerEntity partner,
            ServerPlayer player,
            String previousInstruction,
            String previousQuestion
    ) {
        boolean sameDimension = partner.level() == player.level();
        String inventory = partner.inventorySummary();
        if (inventory.length() > MAX_INVENTORY_SUMMARY_LENGTH) {
            inventory = inventory.substring(0, MAX_INVENTORY_SUMMARY_LENGTH);
        }
        return new MaidControlContextSnapshot(
                partner.getName().getString(),
                partner.level().dimension().identifier().toString(),
                sameDimension ? roundOneDecimal(Math.sqrt(partner.distanceToSqr(player))) : -1.0,
                partner.getHealth(),
                partner.getMaxHealth(),
                partner.getMode().name(),
                partner.workflowSummary(),
                partner.getCurrentContract().map(contract -> contract.status().name()).orElse("NONE"),
                partner.getCurrentContract().map(contract -> contract.job().type().name()).orElse("NONE"),
                partner.getWorkMode().serializedName(),
                partner.getWorkExecutionState(),
                partner.getScheduleType().name(),
                partner.getScheduleActivity().name(),
                partner.getCombatPolicy().serializedName(),
                partner.isHomeBound(),
                partner.getActivityRadius(),
                inventory,
                bounded(previousInstruction, 512),
                bounded(previousQuestion, 240)
        );
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
