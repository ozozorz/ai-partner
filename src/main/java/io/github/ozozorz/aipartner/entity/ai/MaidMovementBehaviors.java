package io.github.ozozorz.aipartner.entity.ai;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModMemoryModules;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;

/**
 * 把跟随、待命和日程位置转换为 LOOK_TARGET/WALK_TARGET；实际寻路只由原版移动汇接器推进。
 */
public final class MaidMovementBehaviors {
    private static final double FOLLOW_START_DISTANCE_SQUARED = 5.0 * 5.0;
    private static final double FOLLOW_STOP_DISTANCE_SQUARED = 3.0 * 3.0;
    private static final double FOLLOW_TELEPORT_DISTANCE_SQUARED = 12.0 * 12.0;
    private static final long TELEPORT_AFTER_UNREACHABLE_TICKS = 60L;
    private static final long FAIL_AFTER_UNREACHABLE_TICKS = 200L;

    private MaidMovementBehaviors() {
    }

    /**
     * GUI 暂停期间清除高层移动意图，并停止正在执行的导航。
     */
    public static OneShot<AiPartnerEntity> pause() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(ModMemoryModules.PAUSED),
                                instance.registered(MemoryModuleType.WALK_TARGET),
                                instance.registered(MemoryModuleType.LOOK_TARGET),
                                instance.registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                        )
                        .apply(instance, (paused, walkTarget, lookTarget, cantReach) ->
                                (level, maid, timestamp) -> {
                                    walkTarget.erase();
                                    lookTarget.erase();
                                    cantReach.erase();
                                    maid.getNavigation().stop();
                                    return true;
                                })
        );
    }

    /**
     * 跟随主人时使用启停滞回，并在原版不可达记忆持续后尝试安全传送。
     */
    public static OneShot<AiPartnerEntity> followOwner() {
        return new FollowOwnerBehavior();
    }

    /**
     * 待命活动持续清除移动意图，避免旧路径在指令切换后继续运行。
     */
    public static OneShot<AiPartnerEntity> stayInPlace() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(ModMemoryModules.STAY_IN_PLACE),
                                instance.absent(ModMemoryModules.PAUSED),
                                instance.registered(MemoryModuleType.WALK_TARGET),
                                instance.registered(MemoryModuleType.LOOK_TARGET)
                        )
                        .apply(instance, (stay, notPaused, walkTarget, lookTarget) ->
                                (level, maid, timestamp) -> {
                                    walkTarget.erase();
                                    lookTarget.erase();
                                    maid.getNavigation().stop();
                                    return true;
                                })
        );
    }

    /**
     * 把日程/回家位置记忆翻译成原版位置追踪器和行走目标。
     */
    public static OneShot<AiPartnerEntity> moveToActivityTarget(float speedModifier) {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(ModMemoryModules.ACTIVITY_TARGET),
                                instance.absent(ModMemoryModules.PAUSED),
                                instance.absent(ModMemoryModules.TASK_CONTROLLED),
                                instance.registered(MemoryModuleType.WALK_TARGET)
                        )
                        .apply(instance, (activityTarget, notPaused, notTaskControlled, walkTarget) ->
                                (level, maid, timestamp) -> {
                                    GlobalPos target = instance.get(activityTarget);
                                    if (target.dimension() != level.dimension()) {
                                        activityTarget.erase();
                                        walkTarget.erase();
                                        return false;
                                    }
                                    if (target.pos().distManhattan(maid.blockPosition()) <= 1) {
                                        activityTarget.erase();
                                        walkTarget.erase();
                                        maid.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                                        return true;
                                    }
                                    BlockPosTracker tracker = new BlockPosTracker(target.pos());
                                    maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
                                    walkTarget.set(new WalkTarget(tracker, speedModifier, 1));
                                    return true;
                                })
        );
    }

    /**
     * 每个 Brain 拥有独立实例，因此可在行为内部记录有限的传送失败次数。
     */
    private static final class FollowOwnerBehavior extends OneShot<AiPartnerEntity> {
        private static final Set<MemoryModuleType<?>> REQUIRED_MEMORIES = Set.of(
                ModMemoryModules.FOLLOW_OWNER,
                ModMemoryModules.PAUSED,
                ModMemoryModules.TASK_CONTROLLED,
                MemoryModuleType.WALK_TARGET,
                MemoryModuleType.LOOK_TARGET,
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE
        );

        private UUID trackedOwner;
        private int teleportFailures;
        private boolean followingMovement;

        @Override
        public boolean trigger(ServerLevel level, AiPartnerEntity maid, long timestamp) {
            Brain<AiPartnerEntity> brain = maid.getBrain();
            if (!brain.checkMemory(ModMemoryModules.FOLLOW_OWNER, MemoryStatus.VALUE_PRESENT)
                    || !brain.checkMemory(ModMemoryModules.PAUSED, MemoryStatus.VALUE_ABSENT)
                    || !brain.checkMemory(ModMemoryModules.TASK_CONTROLLED, MemoryStatus.VALUE_ABSENT)) {
                followingMovement = false;
                return false;
            }

            LivingEntity owner = maid.getOwner();
            if (owner == null || !owner.isAlive() || owner.level() != level) {
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                return false;
            }
            if (!owner.getUUID().equals(trackedOwner)) {
                trackedOwner = owner.getUUID();
                teleportFailures = 0;
                followingMovement = false;
            }

            double distanceSquared = maid.distanceToSqr(owner);
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(owner, true));
            if (distanceSquared > FOLLOW_START_DISTANCE_SQUARED) {
                followingMovement = true;
            } else if (distanceSquared <= FOLLOW_STOP_DISTANCE_SQUARED) {
                followingMovement = false;
            }
            if (followingMovement) {
                float speed = distanceSquared > 100.0 ? 1.25F : 1.05F;
                brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(owner, speed, 3));
            } else {
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                teleportFailures = 0;
                return true;
            }

            brain.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).ifPresent(unreachableSince -> {
                long unreachableTicks = timestamp - unreachableSince;
                if (distanceSquared >= FOLLOW_TELEPORT_DISTANCE_SQUARED
                        && unreachableTicks >= TELEPORT_AFTER_UNREACHABLE_TICKS) {
                    double beforeTeleport = distanceSquared;
                    maid.tryToTeleportToOwner();
                    if (maid.distanceToSqr(owner) < beforeTeleport) {
                        teleportFailures = 0;
                        brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                    } else {
                        teleportFailures++;
                        brain.setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, timestamp);
                        if (teleportFailures > maid.getMaximumLocalRetries()) {
                            maid.failActiveContract(FailureCode.PATH_UNREACHABLE);
                        }
                    }
                } else if (unreachableTicks >= FAIL_AFTER_UNREACHABLE_TICKS) {
                    maid.failActiveContract(FailureCode.PATH_UNREACHABLE);
                }
            });
            return true;
        }

        @Override
        public Set<MemoryModuleType<?>> getRequiredMemories() {
            return REQUIRED_MEMORIES;
        }
    }
}
