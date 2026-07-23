package io.github.ozozorz.aipartner.entity.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModMemoryModules;
import io.github.ozozorz.aipartner.registry.ModSensorTypes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.DoNothing;
import net.minecraft.world.entity.ai.behavior.InteractWithDoor;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.RandomStroll;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTarget;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromLookTarget;
import net.minecraft.world.entity.ai.behavior.Swim;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;

/**
 * 组装女仆的原版式 Brain：传感器写记忆、Activity 选择行为、核心汇接器执行移动与注视。
 */
public final class MaidAi {
    private static final List<SensorType<? extends Sensor<? super AiPartnerEntity>>> SENSOR_TYPES = List.of(
            SensorType.HURT_BY,
            SensorType.NEAREST_LIVING_ENTITIES,
            ModSensorTypes.MAID_THREAT,
            ModSensorTypes.MAID_STATE
    );
    private static final Brain.Provider<AiPartnerEntity> BRAIN_PROVIDER =
            Brain.provider(SENSOR_TYPES, ignored -> createActivities());

    private MaidAi() {
    }

    /**
     * 返回实体构造与存档恢复共用的 Brain Provider。
     */
    public static Brain.Provider<AiPartnerEntity> brainProvider() {
        return BRAIN_PROVIDER;
    }

    /**
     * 依据记忆优先选择战斗、工作、休息或空闲活动，并同步显示状态。
     */
    public static void updateActivity(AiPartnerEntity maid) {
        Brain<AiPartnerEntity> brain = maid.getBrain();
        brain.setActiveActivityToFirstValid(List.of(
                Activity.FIGHT,
                Activity.WORK,
                Activity.REST,
                Activity.IDLE
        ));
        maid.onBrainActivityChanged(brain.getActiveNonCoreActivity().orElse(Activity.IDLE));
    }

    private static List<ActivityData<AiPartnerEntity>> createActivities() {
        return List.of(
                createCoreActivity(),
                createFightActivity(),
                createWorkActivity(),
                createRestActivity(),
                createIdleActivity()
        );
    }

    private static ActivityData<AiPartnerEntity> createCoreActivity() {
        return ActivityData.create(
                Activity.CORE,
                0,
                ImmutableList.<BehaviorControl<? super AiPartnerEntity>>of(
                        MaidMovementBehaviors.pause(),
                        new Swim<>(0.8F),
                        new LookAtTargetSink(45, 90),
                        new MoveToTargetSink(),
                        InteractWithDoor.create()
                )
        );
    }

    private static ActivityData<AiPartnerEntity> createFightActivity() {
        Set<Pair<MemoryModuleType<?>, MemoryStatus>> conditions = ImmutableSet.of(
                Pair.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT),
                Pair.of(ModMemoryModules.PAUSED, MemoryStatus.VALUE_ABSENT)
        );
        Set<MemoryModuleType<?>> cleanup = ImmutableSet.of(
                MemoryModuleType.WALK_TARGET,
                MemoryModuleType.LOOK_TARGET,
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                MemoryModuleType.ATTACK_COOLING_DOWN
        );
        return ActivityData.create(
                Activity.FIGHT,
                ActivityData.createPriorityPairs(
                        0,
                        ImmutableList.<BehaviorControl<? super AiPartnerEntity>>of(
                                MaidCombatBehaviors.validateTarget(),
                                MaidCombatBehaviors.approachTarget(),
                                MaidCombatBehaviors.rangedAttack(),
                                MaidCombatBehaviors.meleeAttack()
                        )
                ),
                conditions,
                cleanup
        );
    }

    private static ActivityData<AiPartnerEntity> createWorkActivity() {
        return ActivityData.create(
                Activity.WORK,
                0,
                ImmutableList.<BehaviorControl<? super AiPartnerEntity>>of(
                        MaidMovementBehaviors.moveToActivityTarget(0.9F),
                        SetEntityLookTarget.create(8.0F),
                        ambientBehaviors()
                ),
                ImmutableSet.of(
                        Pair.of(ModMemoryModules.SCHEDULE_WORK, MemoryStatus.VALUE_PRESENT),
                        Pair.of(ModMemoryModules.PAUSED, MemoryStatus.VALUE_ABSENT)
                )
        );
    }

    private static ActivityData<AiPartnerEntity> createRestActivity() {
        return ActivityData.create(
                Activity.REST,
                0,
                ImmutableList.<BehaviorControl<? super AiPartnerEntity>>of(
                        MaidMovementBehaviors.moveToActivityTarget(0.8F),
                        new DoNothing(40, 80)
                ),
                ImmutableSet.of(
                        Pair.of(ModMemoryModules.SCHEDULE_REST, MemoryStatus.VALUE_PRESENT),
                        Pair.of(ModMemoryModules.PAUSED, MemoryStatus.VALUE_ABSENT)
                )
        );
    }

    private static ActivityData<AiPartnerEntity> createIdleActivity() {
        return ActivityData.create(
                Activity.IDLE,
                0,
                ImmutableList.<BehaviorControl<? super AiPartnerEntity>>of(
                        MaidMovementBehaviors.followOwner(),
                        MaidMovementBehaviors.stayInPlace(),
                        MaidMovementBehaviors.moveToActivityTarget(0.9F),
                        SetEntityLookTarget.create(8.0F),
                        ambientBehaviors()
                )
        );
    }

    private static RunOne<AiPartnerEntity> ambientBehaviors() {
        Map<MemoryModuleType<?>, MemoryStatus> entryCondition = Map.of(
                ModMemoryModules.AMBIENT_MOVEMENT,
                MemoryStatus.VALUE_PRESENT,
                ModMemoryModules.PAUSED,
                MemoryStatus.VALUE_ABSENT,
                ModMemoryModules.TASK_CONTROLLED,
                MemoryStatus.VALUE_ABSENT
        );
        return new RunOne<>(
                entryCondition,
                List.of(
                        Pair.of(RandomStroll.stroll(0.65F), 2),
                        Pair.of(SetWalkTargetFromLookTarget.create(0.65F, 3), 2),
                        Pair.of(new DoNothing(30, 60), 1)
                )
        );
    }
}
