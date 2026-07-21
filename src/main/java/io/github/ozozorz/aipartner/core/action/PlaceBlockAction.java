package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 在动作发生的同一 tick 复验替换性、支撑面和实体碰撞后放置方块。
 */
public final class PlaceBlockAction {
    private final AiPartnerEntity partner;

    public PlaceBlockAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    public boolean place(ServerLevel level, BlockPos position, BlockState state) {
        BlockState replaced = level.getBlockState(position);
        if (!replaced.canBeReplaced()
                || !state.canSurvive(level, position)
                || !level.isUnobstructed(state, position, CollisionContext.of((Entity) partner))) {
            return false;
        }
        if (!level.setBlock(position, state, Block.UPDATE_ALL)) {
            return false;
        }
        partner.swing(InteractionHand.MAIN_HAND);
        level.gameEvent(partner, GameEvent.BLOCK_PLACE, position);
        return true;
    }
}
