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
 * 仅在 FOLLOW 契约运行时工作的主人跟随目标。
 *
 * <p>使用启停距离滞回、周期性重算与延迟传送，避免紧贴主人、频繁抖动或一超距就瞬移。</p>
 */
public final class AiPartnerFollowOwnerGoal extends Goal {
    private static final int PATH_RECALCULATION_TICKS = 10;
    private static final int TELEPORT_AFTER_STALLED_TICKS = 60;
    private static final double TELEPORT_DISTANCE_SQUARED = 144.0;

    private final AiPartnerEntity partner;
    private final PathNavigation navigation;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private LivingEntity owner;
    private int ticksUntilPathRecalculation;
    private int stalledTicks;
    private int teleportFailures;
    private double lastMeasuredDistanceSquared;
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
        if (!partner.isFollowing()
                || partner.isInventoryMenuOpen()
                || currentOwner == null
                || !currentOwner.isAlive()) {
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
                && !partner.isInventoryMenuOpen()
                && owner != null
                && owner.isAlive()
                && partner.distanceToSqr(owner) > stopDistance * stopDistance;
    }

    @Override
    public void start() {
        ticksUntilPathRecalculation = 0;
        stalledTicks = 0;
        teleportFailures = 0;
        lastMeasuredDistanceSquared = owner == null ? 0.0 : partner.distanceToSqr(owner);
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

        ticksUntilPathRecalculation = adjustedTickDelay(PATH_RECALCULATION_TICKS);
        double distanceSquared = partner.distanceToSqr(owner);
        if (distanceSquared + 0.25 < lastMeasuredDistanceSquared) {
            stalledTicks = 0;
        } else if (navigation.isDone() || navigation.isStuck()) {
            stalledTicks += PATH_RECALCULATION_TICKS;
        }
        lastMeasuredDistanceSquared = distanceSquared;

        if (distanceSquared >= TELEPORT_DISTANCE_SQUARED && stalledTicks >= TELEPORT_AFTER_STALLED_TICKS) {
            double distanceBeforeTeleport = distanceSquared;
            partner.tryToTeleportToOwner();
            if (partner.distanceToSqr(owner) < distanceBeforeTeleport) {
                stalledTicks = 0;
                teleportFailures = 0;
            } else if (++teleportFailures > partner.getMaximumLocalRetries()) {
                partner.failActiveContract(FailureCode.PATH_UNREACHABLE);
            }
            return;
        }

        double adjustedSpeed = distanceSquared > 100.0 ? speedModifier * 1.2 : speedModifier;
        if (!navigation.moveTo(owner, adjustedSpeed)) {
            stalledTicks += PATH_RECALCULATION_TICKS;
        }
    }
}
