package io.github.ozozorz.aipartner.inventory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 保存 v0.6 的 35 格储物区，并把 v0.4/v0.5 的首个旧背包物品迁入原生主手。
 */
public final class MaidInventoryPersistence {
    public static final int STORAGE_SLOT_COUNT = 35;
    private static final int STORAGE_LAYOUT_DATA_VERSION = 2;
    private static final String STORAGE_TAG = "MaidStorage";
    private static final String LEGACY_INVENTORY_TAG = "Inventory";

    private MaidInventoryPersistence() {
    }

    public static void save(SimpleContainer storage, ValueOutput output) {
        ContainerHelper.saveAllItems(output.child(STORAGE_TAG), storage.items);
    }

    /**
     * 返回迁移后的主手和无法安全放入的溢出物品；调用方在实体进入世界后再掉落溢出物。
     */
    public static LoadResult load(
            SimpleContainer storage,
            ItemStack currentMainHand,
            ValueInput input,
            int dataVersion
    ) {
        storage.clearContent();
        if (dataVersion >= STORAGE_LAYOUT_DATA_VERSION) {
            Optional<ValueInput> savedStorage = input.child(STORAGE_TAG);
            if (savedStorage.isPresent()) {
                ContainerHelper.loadAllItems(savedStorage.get(), storage.items);
                return new LoadResult(currentMainHand, List.of(), false);
            }
            if (input.list(STORAGE_TAG, ItemStack.CODEC).isPresent()) {
                // 兼容 v0.6 开发快照短暂使用过的压缩列表格式。
                input.list(STORAGE_TAG, ItemStack.CODEC).ifPresent(storage::fromItemList);
                return new LoadResult(currentMainHand, List.of(), false);
            }
        }

        List<ItemStack> legacyItems = new ArrayList<>();
        for (ItemStack stack : input.listOrEmpty(LEGACY_INVENTORY_TAG, ItemStack.CODEC)) {
            if (!stack.isEmpty()) {
                legacyItems.add(stack.copy());
            }
        }
        if (legacyItems.isEmpty()) {
            return new LoadResult(currentMainHand, List.of(), false);
        }

        Iterator<ItemStack> iterator = legacyItems.iterator();
        ItemStack legacyMainHand = iterator.next();
        int storageIndex = 0;
        while (iterator.hasNext() && storageIndex < storage.getContainerSize()) {
            storage.setItem(storageIndex++, iterator.next());
        }

        List<ItemStack> overflow = new ArrayList<>();
        while (iterator.hasNext()) {
            overflow.add(iterator.next());
        }
        ItemStack migratedMainHand = currentMainHand;
        if (currentMainHand.isEmpty()) {
            migratedMainHand = legacyMainHand;
        } else {
            ItemStack remainder = storage.addItem(legacyMainHand);
            if (!remainder.isEmpty()) {
                overflow.add(remainder);
            }
        }
        return new LoadResult(migratedMainHand, List.copyOf(overflow), true);
    }

    /**
     * 描述一次物品布局加载或旧存档迁移结果。
     */
    public record LoadResult(ItemStack mainHand, List<ItemStack> overflow, boolean migratedLegacyInventory) {
    }
}
