package io.github.ozozorz.aipartner.inventory;

import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.order.MaidOrderService;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMenuAction;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.registry.ModMenus;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * AI 女仆的服务端权威背包菜单，包含 36 格储物区、四格护甲、副手和玩家背包。
 */
public final class AiPartnerMenu extends AbstractContainerMenu {
    public static final int STORAGE_SLOT_COUNT = 36;
    public static final int EQUIPMENT_SLOT_COUNT = AiPartnerEquipmentContainer.SLOT_COUNT;
    public static final int PARTNER_SLOT_END = STORAGE_SLOT_COUNT + EQUIPMENT_SLOT_COUNT;
    public static final int PLAYER_SLOT_START = PARTNER_SLOT_END;
    public static final int PLAYER_MAIN_SLOT_END = PLAYER_SLOT_START + 27;
    public static final int PLAYER_SLOT_END = PLAYER_SLOT_START + 36;

    public static final int STORAGE_LEFT = 114;
    public static final int STORAGE_TOP = 20;
    public static final int EQUIPMENT_LEFT = 8;
    public static final int EQUIPMENT_TOP = 148;
    public static final int PLAYER_LEFT = 114;
    public static final int PLAYER_TOP = 140;
    public static final int SCREEN_WIDTH = 284;
    public static final int SCREEN_HEIGHT = 230;

    private static final int DATA_MODE = 0;
    private static final int DATA_JOB = 1;
    private static final int DATA_CONTRACT_STATUS = 2;
    private static final int DATA_HEALTH = 3;
    private static final int DATA_MAX_HEALTH = 4;
    private static final int DATA_COUNT = 5;

    private final @Nullable AiPartnerEntity partner;
    private final ContainerData stateData;

    /**
     * 客户端工厂构造器；实体编号来自 Fabric 扩展菜单的打开数据。
     */
    public AiPartnerMenu(int containerId, Inventory playerInventory, Integer entityId) {
        this(containerId, playerInventory, resolvePartner(playerInventory, entityId), createClientStateData(), false);
    }

    /**
     * 服务端构造器，直接连接实体真实物品栏和动态状态数据。
     */
    public AiPartnerMenu(int containerId, Inventory playerInventory, AiPartnerEntity partner) {
        this(containerId, playerInventory, partner, createLiveStateData(partner), true);
    }

    private AiPartnerMenu(
            int containerId,
            Inventory playerInventory,
            @Nullable AiPartnerEntity partner,
            ContainerData stateData,
            boolean serverSide
    ) {
        super(ModMenus.AI_PARTNER, containerId);
        this.partner = partner;
        this.stateData = stateData;
        Container storage = partner == null ? new SimpleContainer(STORAGE_SLOT_COUNT) : partner.getInventory();
        Container equipment = partner == null
                ? new SimpleContainer(EQUIPMENT_SLOT_COUNT)
                : new AiPartnerEquipmentContainer(partner);

        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        storage,
                        column + row * 9,
                        STORAGE_LEFT + column * 18,
                        STORAGE_TOP + row * 18
                ));
            }
        }

        for (int slotIndex = 0; slotIndex < 4; slotIndex++) {
            EquipmentSlot equipmentSlot = AiPartnerEquipmentContainer.slotAt(slotIndex);
            addSlot(new PartnerEquipmentSlot(
                    equipment,
                    slotIndex,
                    EQUIPMENT_LEFT + slotIndex * 18,
                    EQUIPMENT_TOP,
                    partner,
                    equipmentSlot,
                    emptySlotIcon(equipmentSlot)
            ));
        }
        addSlot(new PartnerEquipmentSlot(
                equipment,
                4,
                EQUIPMENT_LEFT + 4 * 18,
                EQUIPMENT_TOP,
                partner,
                EquipmentSlot.OFFHAND,
                InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD
        ));

        addStandardInventorySlots(playerInventory, PLAYER_LEFT, PLAYER_TOP);
        addDataSlots(stateData);
        if (serverSide && partner != null) {
            partner.onInventoryMenuOpened(playerInventory.player);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return partner != null
                && partner.isAlive()
                && partner.isOwnedBy(player)
                && player.distanceToSqr(partner) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot sourceSlot = slots.get(slotIndex);
        if (!sourceSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack source = sourceSlot.getItem();
        ItemStack original = source.copy();
        if (slotIndex < PARTNER_SLOT_END) {
            if (!moveItemStackTo(source, PLAYER_SLOT_START, PLAYER_SLOT_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean movedToEquipment = tryQuickEquip(source);
            if (!movedToEquipment && !moveItemStackTo(source, 0, STORAGE_SLOT_COUNT, false)) {
                boolean movedWithinPlayer = slotIndex < PLAYER_MAIN_SLOT_END
                        ? moveItemStackTo(source, PLAYER_MAIN_SLOT_END, PLAYER_SLOT_END, false)
                        : moveItemStackTo(source, PLAYER_SLOT_START, PLAYER_MAIN_SLOT_END, false);
                if (!movedWithinPlayer) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (source.isEmpty()) {
            sourceSlot.setByPlayer(ItemStack.EMPTY, original);
        } else {
            sourceSlot.setChanged();
        }
        if (source.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        sourceSlot.onTake(player, source);
        return original;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        Optional<PartnerMenuAction> action = PartnerMenuAction.fromButtonId(buttonId);
        if (action.isEmpty()
                || partner == null
                || !(player instanceof ServerPlayer serverPlayer)
                || !stillValid(player)) {
            return false;
        }

        JobSpec candidate = JobSpec.basic(action.get().jobType());
        String rawInstruction = "ui_button:" + action.get().name().toLowerCase();
        ContractDecision decision = MaidOrderService.submit(
                partner,
                serverPlayer,
                candidate,
                rawInstruction,
                TaskExecutionPolicy.standard("DIRECT_UI")
        );
        if (!decision.accepted()) {
            serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(decision.messageKey()));
            return false;
        }

        serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(action.get().responseKey()));
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide() && partner != null) {
            partner.onInventoryMenuClosed(player);
        }
    }

    public @Nullable AiPartnerEntity partner() {
        return partner;
    }

    public PartnerMode displayedMode() {
        return enumByOrdinal(PartnerMode.values(), stateData.get(DATA_MODE), PartnerMode.IDLE);
    }

    public @Nullable JobType displayedJob() {
        return nullableEnumByOrdinal(JobType.values(), stateData.get(DATA_JOB));
    }

    public @Nullable ContractStatus displayedContractStatus() {
        return nullableEnumByOrdinal(ContractStatus.values(), stateData.get(DATA_CONTRACT_STATUS));
    }

    public float displayedHealth() {
        return stateData.get(DATA_HEALTH) / 10.0F;
    }

    public float displayedMaxHealth() {
        return stateData.get(DATA_MAX_HEALTH) / 10.0F;
    }

    private boolean tryQuickEquip(ItemStack itemStack) {
        if (partner == null || itemStack.isEmpty()) {
            return false;
        }
        EquipmentSlot equipmentSlot = partner.getEquipmentSlotForItem(itemStack);
        int menuSlot = switch (equipmentSlot) {
            case HEAD -> STORAGE_SLOT_COUNT;
            case CHEST -> STORAGE_SLOT_COUNT + 1;
            case LEGS -> STORAGE_SLOT_COUNT + 2;
            case FEET -> STORAGE_SLOT_COUNT + 3;
            case OFFHAND -> STORAGE_SLOT_COUNT + 4;
            default -> -1;
        };
        return menuSlot >= 0
                && !slots.get(menuSlot).hasItem()
                && slots.get(menuSlot).mayPlace(itemStack)
                && moveItemStackTo(itemStack, menuSlot, menuSlot + 1, false);
    }

    private static ContainerData createLiveStateData(AiPartnerEntity partner) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_MODE -> partner.getMode().ordinal();
                    case DATA_JOB -> partner.getCurrentContract().map(contract -> contract.job().type().ordinal()).orElse(-1);
                    case DATA_CONTRACT_STATUS -> partner.getCurrentContract().map(contract -> contract.status().ordinal()).orElse(-1);
                    case DATA_HEALTH -> Math.round(partner.getHealth() * 10.0F);
                    case DATA_MAX_HEALTH -> Math.round(partner.getMaxHealth() * 10.0F);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // 菜单状态只允许服务端实体写入，客户端数据包不能反向修改实体。
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    private static ContainerData createClientStateData() {
        SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
        data.set(DATA_JOB, -1);
        data.set(DATA_CONTRACT_STATUS, -1);
        return data;
    }

    private static @Nullable AiPartnerEntity resolvePartner(Inventory playerInventory, int entityId) {
        Entity entity = playerInventory.player.level().getEntity(entityId);
        return entity instanceof AiPartnerEntity partner ? partner : null;
    }

    private static Identifier emptySlotIcon(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
            case CHEST -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
            case LEGS -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
            case FEET -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
            default -> InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
        };
    }

    private static <E extends Enum<E>> E enumByOrdinal(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    private static <E extends Enum<E>> @Nullable E nullableEnumByOrdinal(E[] values, int ordinal) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    /**
     * 限制装备槽物品类型、堆叠数量和绑定诅咒取出规则。
     */
    private static final class PartnerEquipmentSlot extends Slot {
        private final @Nullable AiPartnerEntity partner;
        private final EquipmentSlot equipmentSlot;
        private final Identifier emptyIcon;

        private PartnerEquipmentSlot(
                Container container,
                int slotIndex,
                int x,
                int y,
                @Nullable AiPartnerEntity partner,
                EquipmentSlot equipmentSlot,
                Identifier emptyIcon
        ) {
            super(container, slotIndex, x, y);
            this.partner = partner;
            this.equipmentSlot = equipmentSlot;
            this.emptyIcon = emptyIcon;
        }

        @Override
        public int getMaxStackSize() {
            return equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? 1 : super.getMaxStackSize();
        }

        @Override
        public boolean mayPlace(ItemStack itemStack) {
            if (equipmentSlot == EquipmentSlot.OFFHAND) {
                return true;
            }
            Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);
            return equippable != null
                    && equippable.slot() == equipmentSlot
                    && (partner == null || partner.isEquippableInSlot(itemStack, equipmentSlot));
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack current = getItem();
            return current.isEmpty()
                    || player.isCreative()
                    || !EnchantmentHelper.has(current, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        }

        @Override
        public Identifier getNoItemIcon() {
            return emptyIcon;
        }
    }
}
