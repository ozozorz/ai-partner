package io.github.ozozorz.aipartner.skill;

import io.github.ozozorz.aipartner.core.action.BreakBlockAction;
import io.github.ozozorz.aipartner.core.action.CraftItemAction;
import io.github.ozozorz.aipartner.core.action.HarvestBlockAction;
import io.github.ozozorz.aipartner.core.action.InteractEntityAction;
import io.github.ozozorz.aipartner.core.action.InventoryAction;
import io.github.ozozorz.aipartner.core.action.NavigateAction;
import io.github.ozozorz.aipartner.core.action.PickupItemAction;
import io.github.ozozorz.aipartner.core.action.PlaceBlockAction;
import io.github.ozozorz.aipartner.core.action.RangedAttackAction;
import io.github.ozozorz.aipartner.core.action.TransferItemAction;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 每名女仆共享的一组基础技能实现。
 *
 * <p>低层动作只负责原子修改；本类负责把这些动作公开为稳定技能，并保存跨工作使用的容器记忆。</p>
 */
public final class MaidSkillSet {
    private final Set<MaidSkillType> availableSkills =
            Collections.unmodifiableSet(EnumSet.allOf(MaidSkillType.class));
    private final NavigateAction navigation;
    private final BreakBlockAction breakBlock;
    private final TransferItemAction transferItem;
    private final InventoryAction inventory;
    private final HarvestBlockAction harvestBlock;
    private final PlaceBlockAction placeBlock;
    private final InteractEntityAction interactEntity;
    private final RangedAttackAction rangedAttack;
    private final PickupItemAction pickupItem;
    private final CraftItemAction craftItem;
    private final MaidContainerMemory containerMemory;
    private final ContainerInteractionSkill containerInteraction;

    private MaidSkillSet(AiPartnerEntity partner) {
        Objects.requireNonNull(partner, "partner");
        inventory = new InventoryAction(partner);
        breakBlock = new BreakBlockAction(partner);
        transferItem = new TransferItemAction();
        navigation = new NavigateAction(partner);
        harvestBlock = new HarvestBlockAction(partner, inventory);
        placeBlock = new PlaceBlockAction(partner);
        interactEntity = new InteractEntityAction(partner, inventory);
        rangedAttack = new RangedAttackAction(partner, inventory);
        pickupItem = new PickupItemAction(partner);
        craftItem = new CraftItemAction(partner);
        containerMemory = new MaidContainerMemory();
        containerInteraction = new ContainerInteractionSkill(partner, transferItem, containerMemory);
    }

    public static MaidSkillSet create(AiPartnerEntity partner) {
        return new MaidSkillSet(partner);
    }

    public Set<MaidSkillType> availableSkills() {
        return availableSkills;
    }

    public boolean supports(Set<MaidSkillType> requiredSkills) {
        return availableSkills.containsAll(requiredSkills);
    }

    public NavigateAction navigation() {
        return navigation;
    }

    public BreakBlockAction breakBlock() {
        return breakBlock;
    }

    public TransferItemAction transferItem() {
        return transferItem;
    }

    public InventoryAction inventory() {
        return inventory;
    }

    public HarvestBlockAction harvestBlock() {
        return harvestBlock;
    }

    public PlaceBlockAction placeBlock() {
        return placeBlock;
    }

    public InteractEntityAction interactEntity() {
        return interactEntity;
    }

    public RangedAttackAction rangedAttack() {
        return rangedAttack;
    }

    public PickupItemAction pickupItem() {
        return pickupItem;
    }

    public CraftItemAction craftItem() {
        return craftItem;
    }

    public MaidContainerMemory containerMemory() {
        return containerMemory;
    }

    public ContainerInteractionSkill containerInteraction() {
        return containerInteraction;
    }
}
