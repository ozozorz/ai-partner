package io.github.ozozorz.aipartner.entity.goal;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

/**
 * 仅在无任务的空闲模式中触发短距离漫步，让女仆在观察、静止和走动之间自然切换。
 */
public final class AiPartnerIdleWanderGoal extends WaterAvoidingRandomStrollGoal {
    private final AiPartnerEntity partner;

    public AiPartnerIdleWanderGoal(AiPartnerEntity partner, double speedModifier) {
        super(partner, speedModifier, 0.001F);
        this.partner = partner;
        setInterval(100);
    }

    @Override
    public boolean canUse() {
        return partner.canUseAmbientMovement() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return partner.canUseAmbientMovement() && super.canContinueToUse();
    }
}
