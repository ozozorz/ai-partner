package io.github.ozozorz.aipartner.inventory;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 管理女仆的持久装备：自动换上更好的护甲、盾牌和当前场景所需的主手武器。
 */
public final class MaidEquipmentController {
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    );

    private final AiPartnerEntity partner;

    public MaidEquipmentController(AiPartnerEntity partner) {
        this.partner = java.util.Objects.requireNonNull(partner, "partner");
    }

    /**
     * 低频整理装备，避免在菜单编辑或工作技能临时借用工具时改动主手。
     */
    public void tick() {
        if (partner.tickCount % 20 != 0 || partner.isInventoryMenuOpen()) {
            return;
        }
        equipBestArmor();
        equipShield();
        if (!partner.hasActiveCombatTarget()
                && (partner.getMode() == PartnerMode.FOLLOW || partner.getMode() == PartnerMode.STAY)) {
            selectBestMeleeWeapon();
        }
    }

    /**
     * 从 35 格储物区选择每个部位最优的可装备护甲。
     */
    public void equipBestArmor() {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            int bestSlot = findBestStorageSlot(
                    stack -> partner.getEquipmentSlotForItem(stack) == slot,
                    partner.getItemBySlot(slot),
                    slot
            );
            if (bestSlot >= 0) {
                swapStorageWithEquipment(bestSlot, slot);
            }
        }
    }

    /**
     * 副手没有盾牌时，把背包中的第一个可格挡物品换入副手。
     */
    public boolean equipShield() {
        if (isShield(partner.getOffhandItem())) {
            return true;
        }
        SimpleContainer storage = partner.getInventory();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            if (isShield(storage.getItem(slot))) {
                swapStorageWithEquipment(slot, EquipmentSlot.OFFHAND);
                return true;
            }
        }
        return false;
    }

    /**
     * 选择剑或斧中伤害更高的一件，并保持在原生主手槽。
     */
    public boolean selectBestMeleeWeapon() {
        Predicate<ItemStack> melee = stack -> !stack.isEmpty()
                && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES));
        return selectBestMainHand(melee);
    }

    /**
     * 选择弓作为主手武器；箭矢由攻击技能从储物区消耗。
     */
    public boolean selectBestRangedWeapon() {
        return selectBestMainHand(stack -> stack.getItem() instanceof BowItem);
    }

    public static boolean isShield(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.BLOCKS_ATTACKS);
    }

    private boolean selectBestMainHand(Predicate<ItemStack> selector) {
        ItemStack current = partner.getMainHandItem();
        ItemStack baseline = selector.test(current) ? current : ItemStack.EMPTY;
        int bestSlot = findBestStorageSlot(selector, baseline, EquipmentSlot.MAINHAND);
        if (bestSlot >= 0) {
            swapStorageWithEquipment(bestSlot, EquipmentSlot.MAINHAND);
        }
        return selector.test(partner.getMainHandItem());
    }

    private int findBestStorageSlot(
            Predicate<ItemStack> selector,
            ItemStack current,
            EquipmentSlot equipmentSlot
    ) {
        SimpleContainer storage = partner.getInventory();
        ItemStack best = current;
        int bestSlot = -1;
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack candidate = storage.getItem(slot);
            if (selector.test(candidate) && partner.isBetterEquipment(candidate, best, equipmentSlot)) {
                best = candidate;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void swapStorageWithEquipment(int storageSlot, EquipmentSlot equipmentSlot) {
        SimpleContainer storage = partner.getInventory();
        ItemStack previous = partner.getItemBySlot(equipmentSlot);
        ItemStack replacement = storage.removeItemNoUpdate(storageSlot);
        storage.setItem(storageSlot, previous);
        partner.setItemSlot(equipmentSlot, replacement);
        partner.setGuaranteedDrop(equipmentSlot);
        partner.setPersistenceRequired();
        storage.setChanged();
    }
}
