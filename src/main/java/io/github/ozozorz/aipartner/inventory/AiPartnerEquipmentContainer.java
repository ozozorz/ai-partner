package io.github.ozozorz.aipartner.inventory;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把实体原生的四个护甲槽和副手槽适配为菜单可操作的五格容器。
 *
 * <p>装备仍由 {@link AiPartnerEntity} 的原生装备系统保存、同步并提供属性，不复制第二份状态。</p>
 */
public final class AiPartnerEquipmentContainer implements Container {
    public static final int SLOT_COUNT = 5;
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.OFFHAND
    };

    private final AiPartnerEntity partner;

    public AiPartnerEquipmentContainer(AiPartnerEntity partner) {
        this.partner = partner;
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (EquipmentSlot slot : SLOTS) {
            if (!partner.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slotIndex) {
        return validIndex(slotIndex) ? partner.getItemBySlot(SLOTS[slotIndex]) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slotIndex, int count) {
        if (!validIndex(slotIndex) || count <= 0) {
            return ItemStack.EMPTY;
        }
        EquipmentSlot slot = SLOTS[slotIndex];
        ItemStack current = partner.getItemBySlot(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = current.split(count);
        partner.setItemSlot(slot, current.isEmpty() ? ItemStack.EMPTY : current);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slotIndex) {
        if (!validIndex(slotIndex)) {
            return ItemStack.EMPTY;
        }
        EquipmentSlot slot = SLOTS[slotIndex];
        ItemStack removed = partner.getItemBySlot(slot);
        partner.setItemSlot(slot, ItemStack.EMPTY);
        return removed;
    }

    @Override
    public void setItem(int slotIndex, ItemStack itemStack) {
        if (!validIndex(slotIndex)) {
            return;
        }
        EquipmentSlot slot = SLOTS[slotIndex];
        ItemStack normalized = itemStack.copy();
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && normalized.getCount() > 1) {
            normalized.setCount(1);
        }
        partner.setItemSlot(slot, normalized);
        if (!partner.level().isClientSide() && !normalized.isEmpty()) {
            partner.setGuaranteedDrop(slot);
            partner.setPersistenceRequired();
        }
    }

    @Override
    public void setChanged() {
        // 实体装备系统会自行发送装备同步包，无需额外的方块实体脏标记。
    }

    @Override
    public boolean stillValid(Player player) {
        return partner.isAlive()
                && partner.isOwnedBy(player)
                && player.distanceToSqr(partner) <= 64.0;
    }

    @Override
    public boolean canPlaceItem(int slotIndex, ItemStack itemStack) {
        if (!validIndex(slotIndex)) {
            return false;
        }
        EquipmentSlot slot = SLOTS[slotIndex];
        return slot == EquipmentSlot.OFFHAND || partner.isEquippableInSlot(itemStack, slot);
    }

    @Override
    public void clearContent() {
        for (EquipmentSlot slot : SLOTS) {
            partner.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    public static EquipmentSlot slotAt(int slotIndex) {
        if (!validIndex(slotIndex)) {
            throw new IllegalArgumentException("Invalid AI Partner equipment slot " + slotIndex);
        }
        return SLOTS[slotIndex];
    }

    private static boolean validIndex(int slotIndex) {
        return slotIndex >= 0 && slotIndex < SLOTS.length;
    }
}
