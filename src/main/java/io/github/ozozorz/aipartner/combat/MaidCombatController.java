package io.github.ozozorz.aipartner.combat;

import io.github.ozozorz.aipartner.core.action.MaidActions;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 选择合法防御目标并把战斗作为可恢复的高优先级临时中断投影到行为控制器。
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

    public void setPolicy(CombatPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy == CombatPolicy.OFF) {
            clearCombat();
        }
    }

    /**
     * 每 tick 仅从受伤记忆和主人战斗记忆中选择目标，不主动扫描中立生物。
     */
    public void tick() {
        if (!(partner.level() instanceof ServerLevel)
                || policy == CombatPolicy.OFF
                || behaviorController.isInventoryMenuOpen()) {
            clearCombat();
            return;
        }

        LivingEntity target = partner.getTarget();
        if (!isLegalTarget(target)) {
            target = selectThreat();
        }
        if (target == null) {
            clearCombat();
            return;
        }
        if (!behaviorController.isTemporarilyInterrupted()) {
            partner.getNavigation().stop();
        }
        partner.setTarget(target);
        behaviorController.setTemporaryInterruption(PartnerMode.FIGHTING);
    }

    public boolean shouldUseRangedCombat() {
        LivingEntity target = partner.getTarget();
        return isLegalTarget(target)
                && partner.distanceToSqr(target) >= RANGED_SWITCH_DISTANCE_SQUARED
                && hasBowAndArrow();
    }

    public boolean shouldUseMeleeCombat() {
        LivingEntity target = partner.getTarget();
        return isLegalTarget(target) && !shouldUseRangedCombat();
    }

    public boolean hasBowAndArrow() {
        boolean hasBow = partner.getMainHandItem().getItem() instanceof BowItem
                || partner.getInventory().getItems().stream()
                .anyMatch(stack -> stack.getItem() instanceof BowItem);
        return hasBow && partner.getInventory().getItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.ARROWS));
    }

    public boolean performRangedAttack(LivingEntity target, float power) {
        return partner.level() instanceof ServerLevel level
                && isLegalTarget(target)
                && actions.rangedAttack().shoot(level, target, power);
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

    private @Nullable LivingEntity selectThreat() {
        LivingEntity selfAttacker = partner.getLastHurtByMob();
        if (isLegalTarget(selfAttacker)) {
            return selfAttacker;
        }
        if (policy != CombatPolicy.DEFEND_OWNER) {
            return null;
        }
        LivingEntity owner = partner.getOwner();
        if (owner == null || owner.level() != partner.level()) {
            return null;
        }
        LivingEntity ownerAttacker = owner.getLastHurtByMob();
        if (isLegalTarget(ownerAttacker)) {
            return ownerAttacker;
        }
        LivingEntity ownerTarget = owner.getLastHurtMob();
        return ownerTarget instanceof Enemy && isLegalTarget(ownerTarget) ? ownerTarget : null;
    }

    private boolean isLegalTarget(@Nullable LivingEntity target) {
        if (target == null
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

    private boolean sameOwner(AiPartnerEntity other) {
        LivingEntity owner = partner.getOwner();
        return owner != null && other.isOwnedBy(owner);
    }

    private void clearCombat() {
        if (partner.getTarget() != null) {
            partner.setTarget(null);
        }
        behaviorController.clearTemporaryInterruption();
    }
}
