package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.core.schedule.ScheduleController;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
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
 * 在 FOLLOW、STAY、WORK 三种长期模式之下管理日程、活动地点、回家和睡眠。
 */
public final class MaidLifeController {
    private static final int BED_RESCAN_INTERVAL_TICKS = 100;
    private static final int HURT_SLEEP_COOLDOWN_TICKS = 200;
    private static final double ARRIVAL_DISTANCE_SQUARED = 4.0;

    private final AiPartnerEntity partner;
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
    private boolean returningHome;

    public MaidLifeController(AiPartnerEntity partner, MaidGameplayConfig config) {
        this.partner = java.util.Objects.requireNonNull(partner, "partner");
        this.config = java.util.Objects.requireNonNull(config, "config");
        profile = new MaidLifeProfile(config.defaultActivityRadius());
        scheduleController = new ScheduleController(config.scheduleWindows());
    }

    /**
     * 首次归属玩家时记录不会随走动改变的默认活动地点。
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
     * 每个服务端 tick 仲裁长期生活活动；具体工作由技能化工作控制器推进。
     */
    public void tick() {
        if (!(partner.level() instanceof ServerLevel level)) {
            return;
        }
        if (partner.isTame()) {
            initializeDefaultHome();
        }
        updateSchedule(level);

        if (partner.isInventoryMenuOpen()) {
            movementTarget = null;
            partner.getNavigation().stop();
            return;
        }
        if (partner.hasActiveCombatTarget()) {
            movementTarget = null;
            wakeUp();
            return;
        }

        switch (partner.getMode()) {
            case FOLLOW -> {
                returningHome = false;
                releaseHomeRestriction();
                wakeUp();
                movementTarget = null;
            }
            case STAY -> {
                returningHome = false;
                enforceStayAnchor();
                wakeUp();
                movementTarget = null;
            }
            case WORK -> {
                if (returningHome) {
                    wakeUp();
                    handleReturnHome();
                } else {
                    handleScheduledActivity(level);
                }
            }
        }
    }

    /**
     * 模式变化时立即建立待命锚点或解除旧的位置限制。
     */
    public void onModeChanged(PartnerMode mode) {
        wakeUp();
        movementTarget = null;
        returningHome = false;
        if (mode == PartnerMode.STAY) {
            stayAnchor = ActivityLocation.at(partner.level(), partner.blockPosition(), 2);
            enforceStayAnchor();
        } else {
            stayAnchor = null;
            releaseHomeRestriction();
        }
    }

    /**
     * 请求在 WORK 模式内返回当前日程对应地点。
     */
    public void requestReturnHome() {
        returningHome = true;
        wakeUp();
        movementTarget = null;
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
        if (radius < 1 || radius > config.maximumActivityRadius()) {
            throw new IllegalArgumentException("Activity radius exceeds the configured range");
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
     * 返回给 Brain 活动导航行为的当前目标；跨维度地点不会产生导航目标。
     */
    public Optional<BlockPos> movementTarget() {
        return Optional.ofNullable(movementTarget);
    }

    public boolean canUseAmbientMovement() {
        return partner.getMode() == PartnerMode.WORK
                && !partner.hasActiveCombatTarget()
                && currentActivity != ScheduleActivity.SLEEP
                && movementTarget == null;
    }

    /**
     * 返回 WORK 活动边界；未配置时回退到首次归属位置。
     */
    public Optional<ActivityLocation> scheduledWorkBoundary() {
        return profile.locationFor(ScheduleActivity.WORK)
                .filter(location -> location.isIn(partner.level()));
    }

    /**
     * 持续工作只在 WORK 模式、工作时段、已抵达边界且没有 GUI/战斗中断时推进。
     */
    public boolean canPerformScheduledWork() {
        return partner.getMode() == PartnerMode.WORK
                && currentActivity == ScheduleActivity.WORK
                && !returningHome
                && !partner.isInventoryMenuOpen()
                && !partner.hasActiveCombatTarget()
                && scheduledWorkBoundary()
                .map(location -> location.contains(partner.level(), partner.blockPosition()))
                .orElse(false);
    }

    /**
     * 战斗遵循狼式行为，同时尊重 STAY 锚点和 WORK 活动范围。
     */
    public boolean permitsCombatAt(BlockPos position) {
        return switch (partner.getMode()) {
            case FOLLOW -> true;
            case STAY -> stayAnchor != null && stayAnchor.contains(partner.level(), position);
            case WORK -> profile.locationFor(currentActivity)
                    .map(location -> location.contains(partner.level(), position))
                    .orElse(true);
        };
    }

    /**
     * 限制待命和区域约束状态下的后台拾取范围。
     */
    public boolean permitsPickupAt(BlockPos position) {
        return switch (partner.getMode()) {
            case FOLLOW -> true;
            case STAY -> stayAnchor == null || stayAnchor.contains(partner.level(), position);
            case WORK -> !profile.homeBound() || profile.locationFor(currentActivity)
                    .map(location -> location.contains(partner.level(), position))
                    .orElse(true);
        };
    }

    public void save(ValueOutput output) {
        profile.save(output);
        output.putString("CurrentScheduleActivity", currentActivity.name());
        if (stayAnchor != null) {
            stayAnchor.save(output, "StayAnchor");
        }
        output.putLong("SleepBlockedUntil", sleepBlockedUntilGameTime);
        output.putBoolean("ReturningHome", returningHome);
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
        returningHome = input.getBooleanOr("ReturningHome", false);
        movementTarget = null;
        selectedBed = null;
        restingWithoutBed = false;
    }

    private void updateSchedule(ServerLevel level) {
        ScheduleActivity nextActivity = scheduleController.activityAt(
                profile.scheduleType(),
                level.getOverworldClockTime()
        );
        if (nextActivity == currentActivity) {
            return;
        }
        wakeUp();
        selectedBed = null;
        nextBedScanGameTime = 0L;
        currentActivity = nextActivity;
        partner.showSpeechBubble(net.minecraft.network.chat.Component.translatable(
                "bubble.ai-partner.schedule." + nextActivity.name().toLowerCase(java.util.Locale.ROOT)
        ));
    }

    private void handleReturnHome() {
        Optional<ActivityLocation> target = profile.locationFor(currentActivity);
        if (target.isEmpty() || !target.get().isIn(partner.level())) {
            movementTarget = null;
            returningHome = false;
            partner.showSpeechBubble(net.minecraft.network.chat.Component.translatable(
                    "bubble.ai-partner.cannot_return_dimension"
            ));
            return;
        }
        ActivityLocation location = target.get();
        partner.setHomeTo(location.position(), location.radius());
        if (partner.distanceToSqr(location.position().getCenter()) <= ARRIVAL_DISTANCE_SQUARED) {
            movementTarget = null;
            returningHome = false;
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
            if (partner.distanceToSqr(selectedBed.getCenter()) <= ARRIVAL_DISTANCE_SQUARED) {
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
