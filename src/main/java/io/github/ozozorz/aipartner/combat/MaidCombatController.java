package io.github.ozozorz.aipartner.combat;

import io.github.ozozorz.aipartner.core.action.MaidActions;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 提供防御目标合法性、武器策略和单次攻击原语；目标生命周期由 Brain 记忆管理。
 */
public final class MaidCombatController {
    private static final double MAX_COMBAT_DISTANCE_SQUARED = 24.0 * 24.0;
    private static final double RANGED_SWITCH_DISTANCE_SQUARED = 5.0 * 5.0;

    private final AiPartnerEntity partner;
    private final MaidBehaviorController behaviorController;
    private final MaidLifeController lifeController;
    private final MaidActions actions;
    private CombatPolicy policy = CombatPolicy.DEFEND_OWNER;

    public MaidCombatController(
            AiPartnerEntity partner,
            MaidBehaviorController behaviorController,
            MaidLifeController lifeController
    ) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.behaviorController = Objects.requireNonNull(behaviorController, "behaviorController");
        this.lifeController = Objects.requireNonNull(lifeController, "lifeController");
        this.actions = MaidActions.create(partner);
    }

    public CombatPolicy policy() {
        return policy;
    }

    /**
     * 更新防御策略；关闭战斗会立即清除目标与临时活动显示。
     */
    public void setPolicy(CombatPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy == CombatPolicy.OFF) {
            clearCombat();
        }
    }

    /**
     * 从受击记忆和主人战斗记录中选择一个合法威胁。
     */
    public Optional<LivingEntity> selectThreat() {
        LivingEntity selfAttacker = partner.getBrain()
                .getMemory(MemoryModuleType.HURT_BY_ENTITY)
                .orElse(partner.getLastHurtByMob());
        if (isLegalTarget(selfAttacker)) {
            return Optional.of(selfAttacker);
        }
        if (policy != CombatPolicy.DEFEND_OWNER) {
            return Optional.empty();
        }
        LivingEntity owner = partner.getOwner();
        if (owner == null || owner.level() != partner.level()) {
            return Optional.empty();
        }
        LivingEntity ownerAttacker = owner.getLastHurtByMob();
        if (isLegalTarget(ownerAttacker)) {
            return Optional.of(ownerAttacker);
        }
        LivingEntity ownerTarget = owner.getLastHurtMob();
        return ownerTarget instanceof Enemy && isLegalTarget(ownerTarget)
                ? Optional.of(ownerTarget)
                : Optional.empty();
    }

    /**
     * 验证目标存活、阵营、距离与活动边界。
     */
    public boolean isLegalTarget(@Nullable LivingEntity target) {
        if (policy == CombatPolicy.OFF
                || target == null
                || !target.isAlive()
                || target == partner
                || target instanceof Player
                || partner.isOwnedBy(target)
                || partner.isAlliedTo(target)
                || !partner.canAttack(target)
                || partner.distanceToSqr(target) > MAX_COMBAT_DISTANCE_SQUARED
                || !lifeController.permitsCombatAt(target.blockPosition())) {
            return false;
        }
        if (target instanceof AiPartnerEntity other) {
            return !sameOwner(other);
        }
        if (target instanceof TamableAnimal tamable && tamable.getOwner() != null) {
            return tamable.getOwner() != partner.getOwner();
        }
        return true;
    }

    public boolean shouldUseRangedCombat(LivingEntity target) {
        return isLegalTarget(target)
                && partner.distanceToSqr(target) >= RANGED_SWITCH_DISTANCE_SQUARED
                && hasBowAndArrow();
    }

    public boolean shouldUseMeleeCombat(LivingEntity target) {
        return isLegalTarget(target) && !shouldUseRangedCombat(target);
    }

    /**
     * 检查背包或主手中的弓，以及背包里的真实箭矢。
     */
    public boolean hasBowAndArrow() {
        boolean hasBow = partner.getMainHandItem().getItem() instanceof BowItem
                || partner.getInventory().getItems().stream()
                .anyMatch(stack -> stack.getItem() instanceof BowItem);
        return hasBow && partner.getInventory().getItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.ARROWS));
    }

    /**
     * 临时借用弓并发射一次，动作结束后立即安全归还装备。
     */
    public boolean performRangedAttack(LivingEntity target, float power) {
        if (!(partner.level() instanceof ServerLevel level) || !isLegalTarget(target)) {
            return false;
        }
        Optional<EquipmentLease> lease = EquipmentLease.acquire(
                partner,
                stack -> stack.getItem() instanceof BowItem
        );
        if (lease.isEmpty()) {
            return false;
        }
        try (EquipmentLease ignored = lease.get()) {
            return actions.rangedAttack().shoot(level, target, power);
        }
    }

    /**
     * 优先借用剑或斧执行一次近战；没有合适武器时仍允许原版徒手攻击。
     */
    public boolean performMeleeAttack(ServerLevel level, LivingEntity target) {
        if (!isLegalTarget(target)) {
            return false;
        }
        Optional<EquipmentLease> lease = EquipmentLease.acquire(
                partner,
                stack -> !stack.isEmpty()
                        && (stack.typeHolder().is(ItemTags.SWORDS) || stack.typeHolder().is(ItemTags.AXES))
        );
        if (lease.isPresent()) {
            try (EquipmentLease ignored = lease.get()) {
                partner.swing(InteractionHand.MAIN_HAND);
                return partner.doHurtTarget(level, target);
            }
        }
        partner.swing(InteractionHand.MAIN_HAND);
        return partner.doHurtTarget(level, target);
    }

    public void save(ValueOutput output) {
        output.putString("CombatPolicy", policy.serializedName());
    }

    public void load(ValueInput input) {
        policy = CombatPolicy.fromSavedName(input.getStringOr(
                "CombatPolicy",
                CombatPolicy.DEFEND_OWNER.serializedName()
        ));
        clearCombat();
    }

    private boolean sameOwner(AiPartnerEntity other) {
        LivingEntity owner = partner.getOwner();
        return owner != null && other.isOwnedBy(owner);
    }

    private void clearCombat() {
        partner.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        partner.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        partner.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        partner.setTarget(null);
        behaviorController.clearTemporaryInterruption();
    }
}
