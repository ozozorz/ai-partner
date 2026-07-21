package io.github.ozozorz.aipartner.entity.goal;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 只消费生活控制器给出的同维度活动目标；该 Goal 永远不会执行传送。
 */
public final class AiPartnerReturnToActivityGoal extends Goal {
    private static final double ARRIVAL_DISTANCE_SQUARED = 4.0;
    private final AiPartnerEntity partner;
    private final double speedModifier;
    private BlockPos target;
    private int pathRecalculationTicks;

    public AiPartnerReturnToActivityGoal(AiPartnerEntity partner, double speedModifier) {
        this.partner = partner;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (partner.isInventoryMenuOpen() || partner.isSleeping()) {
            return false;
        }
        target = partner.getActivityNavigationTarget().orElse(null);
        return target != null && distanceToTargetSquared() > ARRIVAL_DISTANCE_SQUARED;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos currentTarget = partner.getActivityNavigationTarget().orElse(null);
        if (partner.isInventoryMenuOpen()
                || partner.isSleeping()
                || currentTarget == null
                || !currentTarget.equals(target)) {
            return false;
        }
        return distanceToTargetSquared() > ARRIVAL_DISTANCE_SQUARED;
    }

    @Override
    public void start() {
        pathRecalculationTicks = 0;
        moveToTarget();
    }

    @Override
    public void tick() {
        if (--pathRecalculationTicks <= 0) {
            pathRecalculationTicks = adjustedTickDelay(20);
            moveToTarget();
        }
    }

    @Override
    public void stop() {
        target = null;
        partner.getNavigation().stop();
    }

    private void moveToTarget() {
        if (target != null) {
            partner.getNavigation().moveTo(
                    target.getX() + 0.5,
                    target.getY(),
                    target.getZ() + 0.5,
                    speedModifier
            );
        }
    }

    private double distanceToTargetSquared() {
        return partner.distanceToSqr(
                target.getX() + 0.5,
                target.getY(),
                target.getZ() + 0.5
        );
    }
}
