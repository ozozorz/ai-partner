package io.github.ozozorz.aipartner.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModMemoryModules;
import java.util.Set;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;

/**
 * 每 tick 把权威任务、指令和日程状态投影为 Brain 短期记忆。
 */
public final class MaidStateSensor extends Sensor<AiPartnerEntity> {
    private ManualDirective previousDirective = ManualDirective.NONE;
    private ScheduleActivity previousScheduleActivity = ScheduleActivity.LEISURE;
    private boolean previousPaused;
    private boolean previousTaskControlled;
    private boolean previousActivityTargetPresent;

    public MaidStateSensor() {
        super(1);
    }

    @Override
    protected void doTick(ServerLevel level, AiPartnerEntity maid) {
        Brain<AiPartnerEntity> brain = maid.getBrain();
        boolean paused = maid.isInventoryMenuOpen();
        boolean taskControlled = maid.hasFiniteTaskRunning();
        ManualDirective directive = maid.getManualDirective();
        ScheduleActivity scheduleActivity = maid.getScheduleActivity();
        boolean controlStateChanged = directive != previousDirective
                || scheduleActivity != previousScheduleActivity
                || paused != previousPaused
                || taskControlled != previousTaskControlled;
        if (controlStateChanged) {
            clearMovementIntent(brain);
        }
        setMarker(brain, ModMemoryModules.PAUSED, paused);
        setMarker(brain, ModMemoryModules.TASK_CONTROLLED, taskControlled);

        boolean acceptsAutonomousMovement = !paused && !taskControlled;
        setMarker(
                brain,
                ModMemoryModules.FOLLOW_OWNER,
                acceptsAutonomousMovement && directive == ManualDirective.FOLLOW
        );
        setMarker(
                brain,
                ModMemoryModules.STAY_IN_PLACE,
                acceptsAutonomousMovement && directive == ManualDirective.STAY
        );

        boolean scheduleControlsActivity = acceptsAutonomousMovement && directive == ManualDirective.NONE;
        setMarker(
                brain,
                ModMemoryModules.SCHEDULE_WORK,
                scheduleControlsActivity && scheduleActivity == ScheduleActivity.WORK
        );
        setMarker(
                brain,
                ModMemoryModules.SCHEDULE_REST,
                scheduleControlsActivity && scheduleActivity == ScheduleActivity.SLEEP
        );
        setMarker(
                brain,
                ModMemoryModules.AMBIENT_MOVEMENT,
                scheduleControlsActivity && maid.canUseAmbientMovement()
        );

        boolean activityTargetPresent = acceptsAutonomousMovement
                && directive != ManualDirective.FOLLOW
                && directive != ManualDirective.STAY
                && maid.getActivityNavigationTarget().isPresent();
        if (activityTargetPresent) {
            maid.getActivityNavigationTarget().ifPresentOrElse(
                    target -> brain.setMemory(
                            ModMemoryModules.ACTIVITY_TARGET,
                            GlobalPos.of(level.dimension(), target)
                    ),
                    () -> brain.eraseMemory(ModMemoryModules.ACTIVITY_TARGET)
            );
        } else {
            brain.eraseMemory(ModMemoryModules.ACTIVITY_TARGET);
            if (previousActivityTargetPresent) {
                clearMovementIntent(brain);
            }
        }

        if (paused || taskControlled || directive == ManualDirective.STAY) {
            clearMovementIntent(brain);
        }
        if (paused || directive == ManualDirective.STAY) {
            maid.getNavigation().stop();
        }
        previousDirective = directive;
        previousScheduleActivity = scheduleActivity;
        previousPaused = paused;
        previousTaskControlled = taskControlled;
        previousActivityTargetPresent = activityTargetPresent;
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(
                ModMemoryModules.FOLLOW_OWNER,
                ModMemoryModules.STAY_IN_PLACE,
                ModMemoryModules.PAUSED,
                ModMemoryModules.TASK_CONTROLLED,
                ModMemoryModules.AMBIENT_MOVEMENT,
                ModMemoryModules.SCHEDULE_WORK,
                ModMemoryModules.SCHEDULE_REST,
                ModMemoryModules.ACTIVITY_TARGET,
                MemoryModuleType.WALK_TARGET,
                MemoryModuleType.LOOK_TARGET,
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE
        );
    }

    private static void setMarker(
            Brain<AiPartnerEntity> brain,
            MemoryModuleType<Unit> type,
            boolean present
    ) {
        if (present) {
            brain.setMemory(type, Unit.INSTANCE);
        } else {
            brain.eraseMemory(type);
        }
    }

    private static void clearMovementIntent(Brain<AiPartnerEntity> brain) {
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
    }
}
