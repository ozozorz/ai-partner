package io.github.ozozorz.aipartner.entity.ai;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModMemoryModules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;

/**
 * 女仆战斗行为。目标、冷却、视野和不可达状态全部通过原版 Brain 记忆协调。
 */
public final class MaidCombatBehaviors {
    private static final long TARGET_UNREACHABLE_TIMEOUT_TICKS = 200L;
    private static final double RANGED_HOLD_DISTANCE_SQUARED = 8.0 * 8.0;
    private static final double MAX_RANGED_DISTANCE_SQUARED = 15.0 * 15.0;

    private MaidCombatBehaviors() {
    }

    /**
     * 删除死亡、越界或长期不可达的攻击目标。
     */
    public static OneShot<AiPartnerEntity> validateTarget() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(MemoryModuleType.ATTACK_TARGET),
                                instance.registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                        )
                        .apply(instance, (attackTarget, cantReach) -> (level, maid, timestamp) -> {
                            LivingEntity target = instance.get(attackTarget);
                            boolean timedOut = instance.tryGet(cantReach)
                                    .map(since -> timestamp - since > TARGET_UNREACHABLE_TIMEOUT_TICKS)
                                    .orElse(false);
                            if (!maid.isLegalCombatTarget(target) || timedOut) {
                                maid.clearBrainCombatTarget();
                                return true;
                            }
                            maid.acceptBrainCombatTarget(target);
                            return false;
                        })
        );
    }

    /**
     * 根据当前武器策略写入追击或停距目标，不直接调用导航。
     */
    public static OneShot<AiPartnerEntity> approachTarget() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(MemoryModuleType.ATTACK_TARGET),
                                instance.absent(ModMemoryModules.PAUSED),
                                instance.registered(MemoryModuleType.WALK_TARGET),
                                instance.registered(MemoryModuleType.LOOK_TARGET)
                        )
                        .apply(instance, (attackTarget, notPaused, walkTarget, lookTarget) ->
                                (level, maid, timestamp) -> {
                                    LivingEntity target = instance.get(attackTarget);
                                    if (!maid.isLegalCombatTarget(target)) {
                                        return false;
                                    }

                                    lookTarget.set(new EntityTracker(target, true));
                                    double distanceSquared = maid.distanceToSqr(target);
                                    if (maid.shouldUseRangedCombat(target)
                                            && distanceSquared <= RANGED_HOLD_DISTANCE_SQUARED
                                            && maid.getSensing().hasLineOfSight(target)) {
                                        walkTarget.erase();
                                    } else if (maid.shouldUseMeleeCombat(target)
                                            && maid.isWithinMeleeAttackRange(target)) {
                                        walkTarget.erase();
                                    } else {
                                        int closeEnough = maid.shouldUseRangedCombat(target) ? 7 : 1;
                                        walkTarget.set(new WalkTarget(
                                                new EntityTracker(target, false),
                                                1.1F,
                                                closeEnough
                                        ));
                                    }
                                    return true;
                                })
        );
    }

    /**
     * 在可见、持弓且冷却结束时发射一次真实箭矢。
     */
    public static OneShot<AiPartnerEntity> rangedAttack() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(MemoryModuleType.ATTACK_TARGET),
                                instance.absent(MemoryModuleType.ATTACK_COOLING_DOWN),
                                instance.present(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES),
                                instance.absent(ModMemoryModules.PAUSED)
                        )
                        .apply(instance, (attackTarget, cooldown, visibleEntities, notPaused) ->
                                (level, maid, timestamp) -> {
                                    LivingEntity target = instance.get(attackTarget);
                                    boolean visible = instance.<NearestVisibleLivingEntities>get(visibleEntities)
                                            .contains(target);
                                    if (!visible
                                            || maid.distanceToSqr(target) > MAX_RANGED_DISTANCE_SQUARED
                                            || !maid.shouldUseRangedCombat(target)
                                            || !maid.tryRangedBrainAttack(target, 1.0F)) {
                                        return false;
                                    }
                                    cooldown.setWithExpiry(true, 30L);
                                    return true;
                                })
        );
    }

    /**
     * 在近战范围内借用剑/斧并执行一次原版伤害判定。
     */
    public static OneShot<AiPartnerEntity> meleeAttack() {
        return BehaviorBuilder.create(
                instance -> instance.group(
                                instance.present(MemoryModuleType.ATTACK_TARGET),
                                instance.absent(MemoryModuleType.ATTACK_COOLING_DOWN),
                                instance.present(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES),
                                instance.absent(ModMemoryModules.PAUSED)
                        )
                        .apply(instance, (attackTarget, cooldown, visibleEntities, notPaused) ->
                                (level, maid, timestamp) -> {
                                    LivingEntity target = instance.get(attackTarget);
                                    boolean visible = instance.<NearestVisibleLivingEntities>get(visibleEntities)
                                            .contains(target);
                                    if (!visible
                                            || !maid.shouldUseMeleeCombat(target)
                                            || !maid.isWithinMeleeAttackRange(target)
                                            || !maid.tryMeleeBrainAttack(level, target)) {
                                        return false;
                                    }
                                    cooldown.setWithExpiry(true, 20L);
                                    return true;
                                })
        );
    }
}
