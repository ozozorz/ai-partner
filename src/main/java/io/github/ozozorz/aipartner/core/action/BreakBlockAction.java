package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;

/**
 * 统一执行女仆挥手和服务端方块破坏，便于后续工作共享安全检查入口。
 */
public final class BreakBlockAction {
    private final AiPartnerEntity partner;

    public BreakBlockAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    public void swingMainHand() {
        partner.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * 使用女仆作为破坏者执行一次原版方块修改。
     */
    public boolean destroy(ServerLevel level, BlockPos position) {
        return level.destroyBlock(position, false, partner, Block.UPDATE_LIMIT);
    }
}
