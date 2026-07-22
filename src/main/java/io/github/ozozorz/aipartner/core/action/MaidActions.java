package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;

/**
 * 一个女仆实例共享的基础动作集合。
 */
public record MaidActions(
        NavigateAction navigation,
        BreakBlockAction breakBlock,
        TransferItemAction transferItem,
        InventoryAction inventory,
        HarvestBlockAction harvestBlock,
        PlaceBlockAction placeBlock,
        InteractEntityAction interactEntity,
        RangedAttackAction rangedAttack,
        PickupItemAction pickupItem,
        CraftItemAction craftItem
) {
    public MaidActions {
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(breakBlock, "breakBlock");
        Objects.requireNonNull(transferItem, "transferItem");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(harvestBlock, "harvestBlock");
        Objects.requireNonNull(placeBlock, "placeBlock");
        Objects.requireNonNull(interactEntity, "interactEntity");
        Objects.requireNonNull(rangedAttack, "rangedAttack");
        Objects.requireNonNull(pickupItem, "pickupItem");
        Objects.requireNonNull(craftItem, "craftItem");
    }

    public static MaidActions create(AiPartnerEntity partner) {
        InventoryAction inventory = new InventoryAction(partner);
        BreakBlockAction breakBlock = new BreakBlockAction(partner);
        return new MaidActions(
                new NavigateAction(partner),
                breakBlock,
                new TransferItemAction(),
                inventory,
                new HarvestBlockAction(partner, inventory),
                new PlaceBlockAction(partner),
                new InteractEntityAction(partner, inventory),
                new RangedAttackAction(partner, inventory),
                new PickupItemAction(partner),
                new CraftItemAction(partner)
        );
    }
}
