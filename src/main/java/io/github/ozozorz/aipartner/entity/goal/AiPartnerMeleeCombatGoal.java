package io.github.ozozorz.aipartner.entity.goal;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 在远程条件不成立时追击合法防御目标，并优先借用剑或斧作为近战武器。
 */
public final class AiPartnerMeleeCombatGoal extends MeleeAttackGoal {
    private final AiPartnerEntity partner;
    private EquipmentLease weaponLease;

    public AiPartnerMeleeCombatGoal(AiPartnerEntity partner) {
        super(partner, 1.1, true);
        this.partner = partner;
    }

    @Override
    public boolean canUse() {
        return partner.shouldUseMeleeCombat() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return partner.shouldUseMeleeCombat() && super.canContinueToUse();
    }

    @Override
    public void start() {
        weaponLease = EquipmentLease.acquire(
                partner,
                stack -> !stack.isEmpty()
                        && (stack.typeHolder().is(ItemTags.SWORDS) || stack.typeHolder().is(ItemTags.AXES))
        ).orElse(null);
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (weaponLease != null) {
            weaponLease.close();
            weaponLease = null;
        }
    }
}
