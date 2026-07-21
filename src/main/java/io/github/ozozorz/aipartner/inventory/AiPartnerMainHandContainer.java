package io.github.ozozorz.aipartner.inventory;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把 GUI 的第 0 格直接映射到原生主手装备槽，不维护物品副本。
 */
public final class AiPartnerMainHandContainer implements Container {
    private final AiPartnerEntity partner;

    public AiPartnerMainHandContainer(AiPartnerEntity partner) {
        this.partner = partner;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return getItem(0).isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? partner.getMainHandItem() : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        if (slot != 0 || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack current = partner.getMainHandItem();
        ItemStack removed = current.split(count);
        partner.setItemSlot(EquipmentSlot.MAINHAND, current.isEmpty() ? ItemStack.EMPTY : current);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) {
            return ItemStack.EMPTY;
        }
        ItemStack current = partner.getMainHandItem();
        partner.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) {
            partner.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            if (!partner.level().isClientSide() && !stack.isEmpty()) {
                partner.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                partner.setPersistenceRequired();
            }
        }
    }

    @Override
    public void setChanged() {
        // 原生装备系统负责同步主手变化。
    }

    @Override
    public boolean stillValid(Player player) {
        return partner.isAlive()
                && partner.isOwnedBy(player)
                && player.distanceToSqr(partner) <= 64.0;
    }

    @Override
    public void clearContent() {
        partner.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }
}
