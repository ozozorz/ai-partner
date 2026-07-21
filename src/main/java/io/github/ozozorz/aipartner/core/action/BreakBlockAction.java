package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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
        return destroy(level, position, false);
    }

    /**
     * 使用服务端原版战利品表生成掉落物，并让当前主手承担对应挖掘耐久。
     */
    public boolean destroyWithDrops(ServerLevel level, BlockPos position) {
        return destroy(level, position, true);
    }

    /**
     * 移除火焰等不应触发工具挖掘耐久的瞬时方块。
     */
    public boolean removeWithoutTool(ServerLevel level, BlockPos position) {
        return level.destroyBlock(position, false, partner, Block.UPDATE_LIMIT);
    }

    private boolean destroy(ServerLevel level, BlockPos position, boolean dropResources) {
        BlockState state = level.getBlockState(position);
        boolean destroyed = level.destroyBlock(position, dropResources, partner, Block.UPDATE_LIMIT);
        if (destroyed && !partner.getMainHandItem().isEmpty()) {
            partner.getMainHandItem().getItem().mineBlock(
                    partner.getMainHandItem(),
                    level,
                    state,
                    position,
                    partner
            );
        }
        return destroyed;
    }
}
