package io.github.ozozorz.aipartner.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 以物品和组件完全一致为单位计算容器可用容量，供契约验证和实际转移共享。
 */
public final class InventoryCapacity {
    private InventoryCapacity() {
    }

    /**
     * 判断容器能否完整接收指定数量；零或负数量不需要额外容量。
     */
    public static boolean canAccept(Container container, ItemStack prototype, int quantity) {
        if (quantity <= 0) {
            return true;
        }
        if (prototype.isEmpty()) {
            return false;
        }

        long capacity = 0L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                if (container.canPlaceItem(slot, prototype)) {
                    capacity += container.getMaxStackSize(prototype);
                }
            } else if (ItemStack.isSameItemSameComponents(existing, prototype)
                    && container.canPlaceItem(slot, prototype)) {
                capacity += Math.max(0, container.getMaxStackSize(existing) - existing.getCount());
            }
            if (capacity >= quantity) {
                return true;
            }
        }
        return false;
    }
}
