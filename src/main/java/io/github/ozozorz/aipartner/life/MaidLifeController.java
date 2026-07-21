package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.core.schedule.ScheduleController;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.core.task.MaidTaskRuntime;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 仲裁日程、地点约束、回家和睡眠，并把导航目标交给独立 Goal 执行。
 */
public final class MaidLifeController {
    private static final int BED_RESCAN_INTERVAL_TICKS = 100;
    private static final int HURT_SLEEP_COOLDOWN_TICKS = 200;
    private static final double ARRIVAL_DISTANCE_SQUARED = 4.0;

    private final AiPartnerEntity partner;
    private final MaidBehaviorController behaviorController;
    private final MaidTaskRuntime taskRuntime;
    private final MaidGameplayConfig config;
    private final MaidLifeProfile profile;
    private final ScheduleController scheduleController;

    private ScheduleActivity currentActivity = ScheduleActivity.LEISURE;
    private @Nullable ActivityLocation stayAnchor;
    private @Nullable BlockPos movementTarget;
    private @Nullable BlockPos selectedBed;
    private long nextBedScanGameTime;
    private long sleepBlockedUntilGameTime;
    private boolean restingWithoutBed;

    public MaidLifeController(
            AiPartnerEntity partner,
            MaidBehaviorController behaviorController,
            MaidTaskRuntime taskRuntime,
            MaidGameplayConfig config
    ) {
        this.partner = java.util.Objects.requireNonNull(partner, "partner");
        this.behaviorController = java.util.Objects.requireNonNull(behaviorController, "behaviorController");
        this.taskRuntime = java.util.Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.profile = new MaidLifeProfile(config.defaultActivityRadius());
        this.scheduleController = new ScheduleController(config.scheduleWindows());
    }

    /**
     * 生成成功后记录不会随女仆走动改变的默认家位置。
     */
    public void initializeDefaultHome() {
        if (profile.defaultHome().isEmpty()) {
            profile.setDefaultHome(ActivityLocation.at(
                    partner.level(),
                    partner.blockPosition(),
                    profile.activityRadius()
            ));
        }
    }

    /**
     * 每个服务端 tick 重新计算长期活动；不在这里推进有限任务。
     */
    public void tick() {
        if (!(partner.level() instanceof ServerLevel level)) {
            return;
        }
        initializeDefaultHome();
        ScheduleActivity nextActivity = scheduleController.activityAt(
                profile.scheduleType(),
                level.getOverworldClockTime()
        );
        if (nextActivity != currentActivity) {
            wakeUp();
            selectedBed = null;
            nextBedScanGameTime = 0L;
            currentActivity = nextActivity;
            partner.showSpeechBubble(net.minecraft.network.chat.Component.translatable(
                    "bubble.ai-partner.schedule." + nextActivity.name().toLowerCase(java.util.Locale.ROOT)
            ));
        }

        if (behaviorController.isInventoryMenuOpen()) {
            movementTarget = null;
            partner.getNavigation().stop();
            return;
        }
        if (behaviorController.isTemporarilyInterrupted()) {
            movementTarget = null;
            wakeUp();
            return;
        }

        ManualDirective directive = behaviorController.manualDirective();
        if (taskRuntime.hasFiniteTaskRunning()) {
            releaseHomeRestriction();
            wakeUp();
            movementTarget = null;
            return;
        }
        if (directive == ManualDirective.FOLLOW) {
            releaseHomeRestriction();
            wakeUp();
            movementTarget = null;
            return;
        }
        if (directive == ManualDirective.STAY) {
            enforceStayAnchor();
            wakeUp();
            movementTarget = null;
            return;
        }
        if (directive == ManualDirective.RETURN_HOME) {
            wakeUp();
            handleReturnHome();
            return;
        }

        behaviorController.setBackgroundMode(currentActivity.displayedMode());
        handleScheduledActivity(level);
    }

    /**
     * 在长期指令变化时立即建立待命锚点或解除地点限制。
     */
    public void onManualDirectiveActivated(ManualDirective directive) {
        wakeUp();
        movementTarget = null;
        if (directive == ManualDirective.STAY) {
            stayAnchor = ActivityLocation.at(partner.level(), partner.blockPosition(), 2);
            enforceStayAnchor();
        } else if (directive == ManualDirective.FOLLOW) {
            stayAnchor = null;
            releaseHomeRestriction();
        }
    }

    public void onHurt() {
        if (partner.level() instanceof ServerLevel level) {
            sleepBlockedUntilGameTime = level.getGameTime() + HURT_SLEEP_COOLDOWN_TICKS;
        }
        wakeUp();
    }

    public ScheduleActivity currentActivity() {
        return currentActivity;
    }

    public ScheduleType scheduleType() {
        return profile.scheduleType();
    }

    public void setScheduleType(ScheduleType scheduleType) {
        profile.setScheduleType(scheduleType);
        wakeUp();
    }

    public int ticksUntilNextScheduleTransition() {
        return scheduleController.ticksUntilNextTransition(
                profile.scheduleType(),
                partner.level().getOverworldClockTime()
        );
    }

    public boolean homeBound() {
        return profile.homeBound();
    }

    public void setHomeBound(boolean homeBound) {
        profile.setHomeBound(homeBound);
        if (!homeBound) {
            releaseHomeRestriction();
        }
    }

    public int activityRadius() {
        return profile.activityRadius();
    }

    public void setActivityRadius(int radius) {
        if (radius > config.maximumActivityRadius()) {
            throw new IllegalArgumentException("Activity radius exceeds the configured maximum");
        }
        profile.setActivityRadius(radius);
    }

    public int configuredLocationMask() {
        return profile.configuredLocationMask();
    }

    public void setLocationAtCurrentPosition(ActivityLocationType type) {
        profile.setLocation(type, ActivityLocation.at(
                partner.level(),
                partner.blockPosition(),
                profile.activityRadius()
        ));
        selectedBed = null;
    }

    public void clearLocation(ActivityLocationType type) {
        profile.clearLocation(type);
        selectedBed = null;
    }

    public Optional<ActivityLocation> location(ActivityLocationType type) {
        return profile.location(type);
    }

    /**
     * 返回给活动导航 Goal 的当前目标；跨维度地点不会产生导航目标。
     */
    public Optional<BlockPos> movementTarget() {
        return Optional.ofNullable(movementTarget);
    }

    public boolean canUseAmbientMovement() {
        return !taskRuntime.hasRunningContract()
                && behaviorController.manualDirective() == ManualDirective.NONE
                && !behaviorController.isTemporarilyInterrupted()
                && currentActivity != ScheduleActivity.SLEEP
                && movementTarget == null;
    }

    /**
     * 返回 WORK 活动对应的权威地点；未显式配置时回退到生成时默认家位置。
     */
    public Optional<ActivityLocation> scheduledWorkBoundary() {
        return profile.locationFor(ScheduleActivity.WORK)
                .filter(location -> location.isIn(partner.level()));
    }

    /**
     * 持续工作只能在 WORK 时段、无手动/有限任务/战斗中断且已抵达工作区时推进。
     */
    public boolean canPerformScheduledWork() {
        return currentActivity == ScheduleActivity.WORK
                && !behaviorController.isInventoryMenuOpen()
                && !behaviorController.isTemporarilyInterrupted()
                && !taskRuntime.hasFiniteTaskRunning()
                && behaviorController.manualDirective() == ManualDirective.NONE
                && scheduledWorkBoundary()
                .map(location -> location.contains(partner.level(), partner.blockPosition()))
                .orElse(false);
    }

    /**
     * 战斗目标受待命锚点或当前活动地点约束，FOLLOW 与直接有限任务仅保留距离上限。
     */
    public boolean permitsCombatAt(BlockPos position) {
        ManualDirective directive = behaviorController.manualDirective();
        if (directive == ManualDirective.STAY) {
            return stayAnchor != null && stayAnchor.contains(partner.level(), position);
        }
        if (directive == ManualDirective.FOLLOW || taskRuntime.hasFiniteTaskRunning()) {
            return true;
        }
        return profile.locationFor(currentActivity)
                .map(location -> location.contains(partner.level(), position))
                .orElse(true);
    }

    /**
     * 限制待命和 homeBound 状态下的后台拾取范围。
     */
    public boolean permitsPickupAt(BlockPos position) {
        ManualDirective directive = behaviorController.manualDirective();
        if (directive == ManualDirective.STAY) {
            return stayAnchor == null || stayAnchor.contains(partner.level(), position);
        }
        if (!profile.homeBound() || directive == ManualDirective.FOLLOW || taskRuntime.hasFiniteTaskRunning()) {
            return true;
        }
        return profile.locationFor(currentActivity)
                .map(location -> location.contains(partner.level(), position))
                .orElse(true);
    }

    public void save(ValueOutput output) {
        profile.save(output);
        output.putString("CurrentScheduleActivity", currentActivity.name());
        if (stayAnchor != null) {
            stayAnchor.save(output, "StayAnchor");
        }
        output.putLong("SleepBlockedUntil", sleepBlockedUntilGameTime);
    }

    public void load(ValueInput input) {
        profile.load(input);
        if (profile.activityRadius() > config.maximumActivityRadius()) {
            profile.setActivityRadius(config.maximumActivityRadius());
        }
        currentActivity = parseActivity(input.getStringOr(
                "CurrentScheduleActivity",
                ScheduleActivity.LEISURE.name()
        ));
        stayAnchor = ActivityLocation.load(input, "StayAnchor").orElse(null);
        sleepBlockedUntilGameTime = input.getLongOr("SleepBlockedUntil", 0L);
        movementTarget = null;
        selectedBed = null;
        restingWithoutBed = false;
        behaviorController.setBackgroundMode(currentActivity.displayedMode());
    }

    private void handleReturnHome() {
        Optional<ActivityLocation> target = profile.locationFor(currentActivity);
        if (target.isEmpty() || !target.get().isIn(partner.level())) {
            movementTarget = null;
            behaviorController.activateDirective(ManualDirective.NONE);
            behaviorController.setBackgroundMode(currentActivity.displayedMode());
            partner.showSpeechBubble(net.minecraft.network.chat.Component.translatable(
                    "bubble.ai-partner.cannot_return_dimension"
            ));
            return;
        }
        ActivityLocation location = target.get();
        partner.setHomeTo(location.position(), location.radius());
        if (partner.distanceToSqr(
                location.position().getX() + 0.5,
                location.position().getY(),
                location.position().getZ() + 0.5
        ) <= ARRIVAL_DISTANCE_SQUARED) {
            movementTarget = null;
            behaviorController.activateDirective(ManualDirective.NONE);
            behaviorController.setBackgroundMode(currentActivity.displayedMode());
            return;
        }
        movementTarget = location.position();
    }

    private void handleScheduledActivity(ServerLevel level) {
        Optional<ActivityLocation> location = profile.locationFor(currentActivity);
        boolean enforceLocation = profile.homeBound() || currentActivity == ScheduleActivity.SLEEP;
        if (!enforceLocation || location.isEmpty() || !location.get().isIn(level)) {
            releaseHomeRestriction();
            movementTarget = null;
            wakeUp();
            return;
        }

        ActivityLocation targetLocation = location.get();
        partner.setHomeTo(targetLocation.position(), targetLocation.radius());
        if (currentActivity == ScheduleActivity.SLEEP) {
            handleSleep(level, targetLocation);
            return;
        }
        wakeUp();
        movementTarget = targetLocation.contains(level, partner.blockPosition())
                ? null
                : targetLocation.position();
    }

    private void handleSleep(ServerLevel level, ActivityLocation location) {
        if (level.getGameTime() < sleepBlockedUntilGameTime) {
            wakeUp();
            behaviorController.setBackgroundMode(PartnerMode.RELAXING);
            movementTarget = null;
            return;
        }
        if (partner.isSleeping()) {
            movementTarget = null;
            healWhileSleeping();
            return;
        }
        if (selectedBed == null
                || !isUsableBed(level, selectedBed)
                || level.getGameTime() >= nextBedScanGameTime) {
            selectedBed = findNearestBed(level, location).orElse(null);
            nextBedScanGameTime = level.getGameTime() + BED_RESCAN_INTERVAL_TICKS;
        }
        if (selectedBed != null) {
            if (partner.distanceToSqr(
                    selectedBed.getX() + 0.5,
                    selectedBed.getY() + 0.5,
                    selectedBed.getZ() + 0.5
            ) <= ARRIVAL_DISTANCE_SQUARED) {
                partner.getNavigation().stop();
                partner.startSleeping(selectedBed);
                movementTarget = null;
                restingWithoutBed = false;
            } else {
                leaveRestPose();
                movementTarget = selectedBed;
            }
            return;
        }
        if (!location.contains(level, partner.blockPosition())) {
            leaveRestPose();
            movementTarget = location.position();
            return;
        }
        movementTarget = null;
        partner.getNavigation().stop();
        restingWithoutBed = true;
        if (partner.getPose() == Pose.STANDING) {
            partner.setPose(Pose.CROUCHING);
        }
        healWhileSleeping();
    }

    private Optional<BlockPos> findNearestBed(ServerLevel level, ActivityLocation location) {
        return BlockPos.withinManhattanStream(
                        location.position(),
                        location.radius(),
                        3,
                        location.radius()
                )
                .filter(position -> level.getChunkSource().getChunkNow(
                        SectionPos.blockToSectionCoord(position.getX()),
                        SectionPos.blockToSectionCoord(position.getZ())
                ) != null && isUsableBed(level, position))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(position -> partner.distanceToSqr(position.getCenter())));
    }

    private static boolean isUsableBed(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD
                && !state.getValue(BedBlock.OCCUPIED);
    }

    private void enforceStayAnchor() {
        if (stayAnchor == null || !stayAnchor.isIn(partner.level())) {
            stayAnchor = ActivityLocation.at(partner.level(), partner.blockPosition(), 2);
        }
        partner.setHomeTo(stayAnchor.position(), stayAnchor.radius());
        partner.getNavigation().stop();
    }

    private void healWhileSleeping() {
        if (config.sleepHealAmount() > 0.0F
                && partner.tickCount % config.sleepHealIntervalTicks() == 0
                && partner.getHealth() < partner.getMaxHealth()) {
            partner.heal(config.sleepHealAmount());
        }
    }

    private void releaseHomeRestriction() {
        if (partner.hasHome()) {
            partner.clearHome();
        }
    }

    private void wakeUp() {
        if (partner.isSleeping()) {
            partner.stopSleeping();
        }
        leaveRestPose();
    }

    private void leaveRestPose() {
        if (restingWithoutBed) {
            restingWithoutBed = false;
            if (partner.getPose() == Pose.CROUCHING) {
                partner.setPose(Pose.STANDING);
            }
        }
    }

    private static ScheduleActivity parseActivity(String name) {
        try {
            return ScheduleActivity.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ScheduleActivity.LEISURE;
        }
    }
}
