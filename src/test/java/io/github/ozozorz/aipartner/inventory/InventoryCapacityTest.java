package io.github.ozozorz.aipartner.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 验证容量计算会累计兼容堆叠和空槽，并拒绝超过完整请求数量的任务。
 */
class InventoryCapacityTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindTestItemComponents(Items.OAK_LOG, 64);
        bindTestItemComponents(Items.COBBLESTONE, 64);
    }

    @Test
    void accumulatesPartialStackAndEmptySlots() {
        SimpleContainer inventory = new SimpleContainer(2);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 60));

        assertTrue(InventoryCapacity.canAccept(inventory, new ItemStack(Items.OAK_LOG), 68));
        assertFalse(InventoryCapacity.canAccept(inventory, new ItemStack(Items.OAK_LOG), 69));
    }

    @Test
    void doesNotCountSlotsOccupiedByAnotherItem() {
        SimpleContainer inventory = new SimpleContainer(2);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 60));
        inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 64));

        assertTrue(InventoryCapacity.canAccept(inventory, new ItemStack(Items.OAK_LOG), 4));
        assertFalse(InventoryCapacity.canAccept(inventory, new ItemStack(Items.OAK_LOG), 5));
    }

    @Test
    void treatsNonPositiveRequestAsAlreadySatisfied() {
        SimpleContainer inventory = new SimpleContainer(0);

        assertTrue(InventoryCapacity.canAccept(inventory, new ItemStack(Items.OAK_LOG), 0));
        assertFalse(InventoryCapacity.canAccept(inventory, ItemStack.EMPTY, 1));
    }

    /** 为无数据包的纯 JUnit 环境绑定容量计算所需的最小物品组件。 */
    private static void bindTestItemComponents(Item item, int maxStackSize) {
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        if (!(holder instanceof Holder.Reference<Item> reference) || reference.areComponentsBound()) {
            return;
        }
        reference.bindComponents(DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, maxStackSize)
                .build());
    }
}
