package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 统一封装有限任务的导航启动和停止动作。
 */
public final class NavigateAction {
    private final AiPartnerEntity partner;

    public NavigateAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    /**
     * 向方块中心启动一次原版导航。
     */
    public boolean moveTo(BlockPos position, double speed) {
        return partner.getNavigation().moveTo(
                position.getX() + 0.5,
                position.getY(),
                position.getZ() + 0.5,
                1,
                speed
        );
    }

    public void stop() {
        partner.getNavigation().stop();
    }
}
