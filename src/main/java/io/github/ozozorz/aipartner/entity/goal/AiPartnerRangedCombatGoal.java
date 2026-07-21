package io.github.ozozorz.aipartner.entity.goal;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.item.BowItem;

/**
 * 仅在防御控制器选择远程战术时运行，并安全借用与归还背包中的弓。
 */
public final class AiPartnerRangedCombatGoal extends RangedAttackGoal {
    private final AiPartnerEntity partner;
    private EquipmentLease bowLease;

    public AiPartnerRangedCombatGoal(AiPartnerEntity partner) {
        super(partner, 1.0, 20, 40, 15.0F);
        this.partner = partner;
    }

    @Override
    public boolean canUse() {
        return partner.shouldUseRangedCombat() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return partner.shouldUseRangedCombat() && super.canContinueToUse();
    }

    @Override
    public void start() {
        bowLease = EquipmentLease.acquire(
                partner,
                stack -> stack.getItem() instanceof BowItem
        ).orElse(null);
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (bowLease != null) {
            bowLease.close();
            bowLease = null;
        }
    }
}
