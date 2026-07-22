package io.github.ozozorz.aipartner.job;

import io.github.ozozorz.aipartner.core.action.TransferItemAction;
import io.github.ozozorz.aipartner.inventory.InventoryCapacity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 普通单箱目标的查找、权限检查和确定性物品插入工具。
 */
public final class ContainerTargets {
    public static final int DEFAULT_DEPOSIT_RADIUS = 16;
    public static final int MAX_DEPOSIT_RADIUS = 24;
    private static final int VERTICAL_SEARCH_RADIUS = 6;
    private static final TransferItemAction TRANSFER_ACTION = new TransferItemAction();

    private ContainerTargets() {
    }

    /**
     * 查找最近的、未被阻挡且主人有权打开的普通单箱。
     */
    public static Optional<BlockPos> findNearestChest(
            ServerLevel level,
            BlockPos origin,
            int radius,
            ServerPlayer owner
    ) {
        return BlockPos.findClosestMatch(
                origin,
                radius,
                VERTICAL_SEARCH_RADIUS,
                pos -> resolve(level, pos, owner).isPresent()
        ).map(BlockPos::immutable);
    }

    /**
     * 查找容量足以容纳目标数量的最近可访问单箱。
     */
    public static Optional<BlockPos> findNearestChestWithCapacity(
            ServerLevel level,
            BlockPos origin,
            int radius,
            ServerPlayer owner,
            ItemStack prototype,
            int quantity
    ) {
        return BlockPos.findClosestMatch(
                origin,
                radius,
                VERTICAL_SEARCH_RADIUS,
                pos -> resolve(level, pos, owner)
                        .filter(container -> hasCapacity(container, prototype, quantity))
                        .isPresent()
        ).map(BlockPos::immutable);
    }

    /**
     * 把坐标解析为当前可访问的单箱容器。
     */
    public static Optional<Container> resolve(ServerLevel level, BlockPos pos, ServerPlayer owner) {
        if (level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        ) == null) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)
                || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chestEntity)
                || !chestEntity.canOpen(owner)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getContainer(chestBlock, state, level, pos, false));
    }

    /**
     * 计算容器对同组件物品的可用容量是否满足给定数量。
     */
    public static boolean hasCapacity(Container container, ItemStack prototype, int quantity) {
        return InventoryCapacity.canAccept(container, prototype, quantity);
    }

    /**
     * 尽可能插入给定物品副本，并返回实际插入数量。
     */
    public static int insert(Container container, ItemStack source) {
        return TRANSFER_ACTION.insert(container, source);
    }
}
