package io.github.ozozorz.aipartner.entity.goal;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * 仅在 FOLLOW 契约运行时工作的有限重试跟随目标。
 */
public final class AiPartnerFollowOwnerGoal extends Goal {
    private final AiPartnerEntity partner;
    private final PathNavigation navigation;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private LivingEntity owner;
    private int ticksUntilPathRecalculation;
    private int consecutiveFailures;
    private float oldWaterCost;

    public AiPartnerFollowOwnerGoal(
            AiPartnerEntity partner,
            double speedModifier,
            float startDistance,
            float stopDistance
    ) {
        this.partner = partner;
        this.navigation = partner.getNavigation();
        this.speedModifier = speedModifier;
        this.startDistance = startDistance;
        this.stopDistance = stopDistance;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        if (!(navigation instanceof GroundPathNavigation) && !(navigation instanceof FlyingPathNavigation)) {
            throw new IllegalArgumentException("Unsupported navigation type for AI Partner");
        }
    }

    @Override
    public boolean canUse() {
        LivingEntity currentOwner = partner.getOwner();
        if (!partner.isFollowing() || currentOwner == null || !currentOwner.isAlive()) {
            return false;
        }
        if (partner.distanceToSqr(currentOwner) < startDistance * startDistance) {
            return false;
        }
        owner = currentOwner;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return partner.isFollowing()
                && owner != null
                && owner.isAlive()
                && partner.distanceToSqr(owner) > stopDistance * stopDistance;
    }

    @Override
    public void start() {
        ticksUntilPathRecalculation = 0;
        consecutiveFailures = 0;
        oldWaterCost = partner.getPathfindingMalus(PathType.WATER);
        partner.setPathfindingMalus(PathType.WATER, 0.0F);
    }

    @Override
    public void stop() {
        owner = null;
        navigation.stop();
        partner.setPathfindingMalus(PathType.WATER, oldWaterCost);
    }

    @Override
    public void tick() {
        if (owner == null) {
            return;
        }
        partner.getLookControl().setLookAt(owner, 10.0F, partner.getMaxHeadXRot());
        if (--ticksUntilPathRecalculation > 0) {
            return;
        }

        ticksUntilPathRecalculation = adjustedTickDelay(10);
        if (partner.shouldTryTeleportToOwner()) {
            double distanceBeforeTeleport = partner.distanceToSqr(owner);
            partner.tryToTeleportToOwner();
            recordAttempt(partner.distanceToSqr(owner) < distanceBeforeTeleport);
        } else {
            recordAttempt(navigation.moveTo(owner, speedModifier));
        }
    }

    private void recordAttempt(boolean successful) {
        consecutiveFailures = successful ? 0 : consecutiveFailures + 1;
        if (consecutiveFailures > partner.getMaximumLocalRetries()) {
            partner.failActiveContract(FailureCode.PATH_UNREACHABLE);
        }
    }
}

