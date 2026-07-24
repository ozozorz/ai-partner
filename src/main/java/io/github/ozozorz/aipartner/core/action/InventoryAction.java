package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 为工作规则提供服务端权威的储物区查询、预演和原子取物入口。
 */
public final class InventoryAction {
    private final AiPartnerEntity partner;

    public InventoryAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    public OptionalInt findSlot(Predicate<ItemStack> predicate) {
        SimpleContainer storage = partner.getInventory();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            if (predicate.test(storage.getItem(slot))) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public boolean contains(Predicate<ItemStack> predicate) {
        return findSlot(predicate).isPresent();
    }

    public boolean contains(Item item) {
        return contains(stack -> stack.is(item));
    }

    /**
     * 从指定储物槽取出最多 amount 个物品；调用方负责在后续失败时归还。
     */
    public ItemStack take(int slot, int amount) {
        if (slot < 0 || slot >= partner.getInventory().getContainerSize() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        return partner.getInventory().removeItem(slot, amount);
    }

    public boolean add(ItemStack stack) {
        return stack.isEmpty() || partner.getInventory().addItem(stack).isEmpty();
    }

    /**
     * 在物品尚未写入真实背包前，用副本验证整批结果能否完整容纳。
     */
    public boolean canAddAll(Collection<ItemStack> stacks) {
        SimpleContainer shadow = copyStorage();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && !shadow.addItem(stack.copy()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean canAdd(ItemStack stack) {
        return canAddAll(java.util.List.of(stack));
    }

    public boolean hasAnySpace() {
        SimpleContainer storage = partner.getInventory();
        for (ItemStack stack : storage.getItems()) {
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private SimpleContainer copyStorage() {
        SimpleContainer source = partner.getInventory();
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            copy.setItem(slot, source.getItem(slot).copy());
        }
        return copy;
    }
}
