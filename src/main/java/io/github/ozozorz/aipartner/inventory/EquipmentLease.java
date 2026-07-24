package io.github.ozozorz.aipartner.inventory;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 临时把储物区工具交换到原生主手，并在任务终止时按实际槽位状态安全归还。
 */
public final class EquipmentLease implements AutoCloseable {
    public static final int NO_SOURCE_SLOT = -1;

    private final AiPartnerEntity partner;
    private final Predicate<ItemStack> selector;
    private final int sourceSlot;
    private final ItemStack leasedStackReference;
    private final boolean borrowedFromStorage;
    private boolean closed;

    private EquipmentLease(
            AiPartnerEntity partner,
            Predicate<ItemStack> selector,
            int sourceSlot,
            ItemStack leasedStackReference,
            boolean borrowedFromStorage
    ) {
        this.partner = partner;
        this.selector = selector;
        this.sourceSlot = sourceSlot;
        this.leasedStackReference = leasedStackReference;
        this.borrowedFromStorage = borrowedFromStorage;
    }

    /**
     * 优先复用当前主手，否则与第一个符合条件的储物槽进行原子交换。
     */
    public static Optional<EquipmentLease> acquire(
            AiPartnerEntity partner,
            Predicate<ItemStack> selector
    ) {
        ItemStack mainHand = partner.getMainHandItem();
        if (selector.test(mainHand)) {
            return Optional.of(new EquipmentLease(
                    partner,
                    selector,
                    NO_SOURCE_SLOT,
                    mainHand,
                    false
            ));
        }
        SimpleContainer storage = partner.getInventory();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack candidate = storage.getItem(slot);
            if (!selector.test(candidate)) {
                continue;
            }
            ItemStack previousMainHand = mainHand;
            ItemStack leased = storage.removeItemNoUpdate(slot);
            storage.setItem(slot, previousMainHand);
            partner.setItemSlot(EquipmentSlot.MAINHAND, leased);
            partner.setGuaranteedDrop(EquipmentSlot.MAINHAND);
            return Optional.of(new EquipmentLease(
                    partner,
                    selector,
                    slot,
                    leased,
                    true
            ));
        }
        return Optional.empty();
    }

    /**
     * 为允许徒手降级的工作临时清空主手；已有主手物品只会交换到真实空储物槽。
     */
    public static Optional<EquipmentLease> acquireBareHand(AiPartnerEntity partner) {
        ItemStack mainHand = partner.getMainHandItem();
        if (mainHand.isEmpty()) {
            return Optional.of(new EquipmentLease(
                    partner,
                    ItemStack::isEmpty,
                    NO_SOURCE_SLOT,
                    mainHand,
                    false
            ));
        }
        SimpleContainer storage = partner.getInventory();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            if (!storage.getItem(slot).isEmpty()) {
                continue;
            }
            storage.setItem(slot, mainHand);
            partner.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return Optional.of(new EquipmentLease(
                    partner,
                    ItemStack::isEmpty,
                    slot,
                    partner.getMainHandItem(),
                    true
            ));
        }
        return Optional.empty();
    }

    public boolean isUsable() {
        return !closed && selector.test(partner.getMainHandItem());
    }

    /**
     * 只在主手和来源槽仍符合租约预期时交换；GUI 修改永远不会被覆盖。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!borrowedFromStorage) {
            return;
        }
        SimpleContainer storage = partner.getInventory();
        if (sourceSlot < 0 || sourceSlot >= storage.getContainerSize()) {
            return;
        }
        ItemStack currentMainHand = partner.getMainHandItem();
        ItemStack sourceContents = storage.getItem(sourceSlot);
        if (currentMainHand.isEmpty()) {
            partner.setItemSlot(EquipmentSlot.MAINHAND, sourceContents);
            storage.setItem(sourceSlot, ItemStack.EMPTY);
            return;
        }
        if (currentMainHand != leasedStackReference) {
            return;
        }
        partner.setItemSlot(EquipmentSlot.MAINHAND, sourceContents);
        storage.setItem(sourceSlot, currentMainHand);
    }
}
