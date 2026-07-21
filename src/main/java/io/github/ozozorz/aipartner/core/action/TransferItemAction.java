package io.github.ozozorz.aipartner.core.action;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 在两个服务端容器间确定性移动物品，不复制源堆栈。
 */
public final class TransferItemAction {
    /**
     * 从源容器移动最多 maximum 个指定物品，并返回实际移动量。
     */
    public int moveMatching(Container source, Container destination, Item item, int maximum) {
        int remaining = Math.max(0, maximum);
        int movedTotal = 0;
        for (int slot = 0; slot < source.getContainerSize() && remaining > 0; slot++) {
            ItemStack sourceStack = source.getItem(slot);
            if (sourceStack.isEmpty() || sourceStack.getItem() != item) {
                continue;
            }
            ItemStack offered = sourceStack.copyWithCount(Math.min(sourceStack.getCount(), remaining));
            int inserted = insert(destination, offered);
            if (inserted <= 0) {
                continue;
            }
            sourceStack.shrink(inserted);
            if (sourceStack.isEmpty()) {
                source.setItem(slot, ItemStack.EMPTY);
            } else {
                source.setChanged();
            }
            movedTotal += inserted;
            remaining -= inserted;
        }
        return movedTotal;
    }

    /**
     * 尽可能插入给定物品副本，并返回实际插入数量。
     */
    public int insert(Container container, ItemStack source) {
        if (source.isEmpty()) {
            return 0;
        }
        ItemStack remaining = source.copy();
        int originalCount = remaining.getCount();

        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameComponents(existing, remaining)
                    || !container.canPlaceItem(slot, remaining)) {
                continue;
            }
            int moved = Math.min(
                    remaining.getCount(),
                    container.getMaxStackSize(existing) - existing.getCount()
            );
            if (moved > 0) {
                existing.grow(moved);
                remaining.shrink(moved);
            }
        }

        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, remaining)) {
                continue;
            }
            int moved = Math.min(remaining.getCount(), container.getMaxStackSize(remaining));
            container.setItem(slot, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }

        int inserted = originalCount - remaining.getCount();
        if (inserted > 0) {
            container.setChanged();
        }
        return inserted;
    }
}
