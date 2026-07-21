package io.github.ozozorz.aipartner.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

/**
 * 验证 v0.4/v0.5 背包迁移与 v0.6 新布局往返不会复制或静默丢失物品。
 */
class MaidInventoryPersistenceTest {
    private static HolderLookup.Provider registryLookup;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.Frozen builtIns = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        bindTestItemComponents(Items.WOODEN_AXE, 1);
        bindTestItemComponents(Items.OAK_LOG, 64);
        bindTestItemComponents(Items.COBBLESTONE, 64);
        bindTestItemComponents(Items.DIAMOND_SWORD, 1);
        bindTestItemComponents(Items.BIRCH_LOG, 64);
        bindTestItemComponents(Items.IRON_AXE, 1);
        bindTestItemComponents(Items.SHIELD, 1);
        registryLookup = builtIns;
    }

    /** 为无数据包的纯 JUnit 环境绑定测试所需的最小物品组件。 */
    private static void bindTestItemComponents(Item item, int maxStackSize) {
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        if (!(holder instanceof Holder.Reference<Item> reference) || reference.areComponentsBound()) {
            return;
        }
        DataComponentMap components = DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, maxStackSize)
                .build();
        reference.bindComponents(components);
    }

    @Test
    void migratesFirstLegacyStackIntoNativeMainHand() {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        var legacy = output.list("Inventory", ItemStack.CODEC);
        legacy.add(new ItemStack(Items.WOODEN_AXE));
        legacy.add(new ItemStack(Items.OAK_LOG, 12));

        SimpleContainer storage = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
        MaidInventoryPersistence.LoadResult result = MaidInventoryPersistence.load(
                storage,
                ItemStack.EMPTY,
                input(output),
                1
        );

        assertTrue(result.migratedLegacyInventory());
        assertTrue(result.mainHand().is(Items.WOODEN_AXE));
        assertEquals(12, storage.getItem(0).getCount());
        assertTrue(result.overflow().isEmpty());
    }

    @Test
    void preservesOverflowWhenLegacyInventoryAndNativeMainHandAreFull() {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        var legacy = output.list("Inventory", ItemStack.CODEC);
        for (int index = 0; index < 36; index++) {
            legacy.add(new ItemStack(Items.COBBLESTONE, 64));
        }

        SimpleContainer storage = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
        MaidInventoryPersistence.LoadResult result = MaidInventoryPersistence.load(
                storage,
                new ItemStack(Items.DIAMOND_SWORD),
                input(output),
                1
        );

        assertTrue(result.mainHand().is(Items.DIAMOND_SWORD));
        assertEquals(35 * 64, storage.getItems().stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(64, result.overflow().stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void roundTripsNewStorageWithoutTreatingItAsLegacy() {
        SimpleContainer original = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
        original.setItem(0, new ItemStack(Items.BIRCH_LOG, 8));
        original.setItem(4, new ItemStack(Items.IRON_AXE));
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        MaidInventoryPersistence.save(original, output);

        SimpleContainer restored = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
        MaidInventoryPersistence.LoadResult result = MaidInventoryPersistence.load(
                restored,
                new ItemStack(Items.SHIELD),
                input(output),
                2
        );

        assertFalse(result.migratedLegacyInventory());
        assertTrue(result.mainHand().is(Items.SHIELD));
        assertEquals(8, restored.getItem(0).getCount());
        assertTrue(restored.getItem(4).is(Items.IRON_AXE));
        assertTrue(result.overflow().isEmpty());
    }

    private static ValueInput input(TagValueOutput output) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registryLookup, output.buildResult());
    }
}
