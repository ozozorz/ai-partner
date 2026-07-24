package io.github.ozozorz.aipartner.combat;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.inventory.MaidEquipmentController;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import io.github.ozozorz.aipartner.skill.MaidSkillSet;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import org.jspecify.annotations.Nullable;

/**
 * 实现与原版狼一致的主人防御逻辑，并协调武器切换、追击边界和盾牌格挡。
 */
public final class MaidCombatController {
    private static final double MAX_COMBAT_DISTANCE_SQUARED = 24.0 * 24.0;
    private static final double RANGED_SWITCH_DISTANCE_SQUARED = 5.0 * 5.0;
    private static final double SHIELD_DISTANCE_SQUARED = 6.0 * 6.0;

    private final AiPartnerEntity partner;
    private final MaidLifeController lifeController;
    private final MaidSkillSet skills;
    private final MaidEquipmentController equipment;

    public MaidCombatController(
            AiPartnerEntity partner,
            MaidLifeController lifeController,
            MaidSkillSet skills,
            MaidEquipmentController equipment
    ) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.lifeController = Objects.requireNonNull(lifeController, "lifeController");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
    }

    /**
     * 像狼一样优先自卫；非 STAY 模式还会保护主人并协助主人攻击。
     */
    public Optional<LivingEntity> selectThreat() {
        if (!partner.isTame()) {
            return Optional.empty();
        }
        LivingEntity selfAttacker = partner.getBrain()
                .getMemory(MemoryModuleType.HURT_BY_ENTITY)
                .orElse(partner.getLastHurtByMob());
        if (isLegalTarget(selfAttacker)) {
            return Optional.of(selfAttacker);
        }
        if (partner.getMode() == PartnerMode.STAY) {
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
        return isLegalTarget(ownerTarget) ? Optional.of(ownerTarget) : Optional.empty();
    }

    /**
     * 验证目标存活、阵营、主人 PvP 权限、原版狼排除项、距离与活动边界。
     */
    public boolean isLegalTarget(@Nullable LivingEntity target) {
        LivingEntity owner = partner.getOwner();
        if (!partner.isTame()
                || owner == null
                || target == null
                || !target.isAlive()
                || target == partner
                || partner.isOwnedBy(target)
                || partner.isAlliedTo(target)
                || !partner.canAttack(target)
                || !partner.wantsToAttackLikeWolf(target, owner)
                || partner.distanceToSqr(target) > MAX_COMBAT_DISTANCE_SQUARED
                || !lifeController.permitsCombatAt(target.blockPosition())) {
            return false;
        }
        return !(target instanceof Player player) || !(owner instanceof Player ownerPlayer)
                || ownerPlayer.canHarmPlayer(player);
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
     * 检查主手或储物区中的弓和真实箭矢。
     */
    public boolean hasBowAndArrow() {
        boolean hasBow = partner.getMainHandItem().getItem() instanceof BowItem
                || partner.getInventory().getItems().stream()
                .anyMatch(stack -> stack.getItem() instanceof BowItem);
        return hasBow && partner.getInventory().getItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.is(ItemTags.ARROWS));
    }

    /**
     * 选择弓、结束格挡并发射一次真实箭矢。
     */
    public boolean performRangedAttack(LivingEntity target, float power) {
        if (!(partner.level() instanceof ServerLevel level)
                || !isLegalTarget(target)
                || !equipment.selectBestRangedWeapon()) {
            return false;
        }
        stopBlocking();
        return skills.rangedAttack().shoot(level, target, power);
    }

    /**
     * 选择最优剑/斧并执行一次原版近战伤害；没有武器时允许徒手攻击。
     */
    public boolean performMeleeAttack(ServerLevel level, LivingEntity target) {
        if (!isLegalTarget(target)) {
            return false;
        }
        equipment.selectBestMeleeWeapon();
        stopBlocking();
        partner.swing(InteractionHand.MAIN_HAND);
        return partner.doHurtTarget(level, target);
    }

    /**
     * 敌人接近且尚未到攻击瞬间时，持续用副手盾牌朝向威胁格挡。
     */
    public void updateShieldDefense(LivingEntity target) {
        if (!isLegalTarget(target)
                || partner.distanceToSqr(target) > SHIELD_DISTANCE_SQUARED
                || !partner.getSensing().hasLineOfSight(target)
                || !equipment.equipShield()) {
            stopBlocking();
            return;
        }
        partner.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (!partner.isUsingItem()) {
            partner.startUsingItem(InteractionHand.OFF_HAND);
        }
    }

    /**
     * 清空所有战斗记忆并结束盾牌使用。
     */
    public void clearCombat() {
        partner.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        partner.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        partner.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        partner.setTarget(null);
        stopBlocking();
    }

    private void stopBlocking() {
        if (partner.isUsingItem()
                && partner.getUsedItemHand() == InteractionHand.OFF_HAND
                && MaidEquipmentController.isShield(partner.getUseItem())) {
            partner.stopUsingItem();
        }
    }
}
