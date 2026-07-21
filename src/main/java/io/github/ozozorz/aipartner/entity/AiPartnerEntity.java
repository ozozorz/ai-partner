package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.core.task.MaidTaskRuntime;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerFollowOwnerGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerIdleWanderGoal;
import io.github.ozozorz.aipartner.entity.navigation.AiPartnerPathNavigation;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.registry.ModTasks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * AI 女仆的 Minecraft 实体外壳。
 *
 * <p>实体只负责生命周期、同步字段、物品栏和原版交互；行为仲裁与有限任务由核心运行时管理。</p>
 */
public final class AiPartnerEntity extends TamableAnimal implements InventoryCarrier, ExtendedMenuProvider<Integer> {
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.INT
    );

    private final SimpleContainer inventory = new SimpleContainer(AiPartnerMenu.STORAGE_SLOT_COUNT);
    private final MaidBehaviorController behaviorController = new MaidBehaviorController(this);
    private final MaidTaskRuntime taskRuntime = new MaidTaskRuntime(
            this,
            behaviorController,
            ModTasks.createRegistry()
    );

    public AiPartnerEntity(EntityType<? extends AiPartnerEntity> entityType, Level level) {
        super(entityType, level);
        setCanPickUpLoot(true);
    }

    /**
     * 定义女仆基础生存与移动属性。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new AiPartnerFollowOwnerGoal(this, 1.05, 5.0F, 3.0F));
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(6, new AiPartnerIdleWanderGoal(this, 0.65));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F, 0.035F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /**
     * 使用支持开门、过门和漂浮的核心导航器。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new AiPartnerPathNavigation(this, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, PartnerMode.IDLE.ordinal());
    }

    /**
     * 由 MaidOrderService 调用，把已验证契约交给通用运行时。
     */
    public void applyValidatedContract(
            TaskContract contract,
            ServerPlayer actor,
            String rawInstruction,
            TaskExecutionPolicy executionPolicy
    ) {
        taskRuntime.apply(contract, actor, rawInstruction, executionPolicy);
    }

    /**
     * 由运行时监控器以类型化原因结束当前任务。
     */
    public void failActiveContract(FailureCode failureCode) {
        taskRuntime.fail(failureCode);
    }

    /**
     * 由有限任务在目标谓词成立后完成当前契约。
     */
    public void completeActiveContract() {
        taskRuntime.complete();
    }

    public int getMaximumLocalRetries() {
        return taskRuntime.maximumLocalRetries();
    }

    public boolean usesRuntimeMonitoring() {
        return taskRuntime.runtimeMonitoringEnabled();
    }

    public boolean allowsLocalRecovery() {
        return taskRuntime.localRecoveryEnabled();
    }

    public void recordRuntimeRecovery(String reason) {
        taskRuntime.recordRuntimeRecovery(reason);
    }

    public int getRuntimeRecoveryCount() {
        return taskRuntime.runtimeRecoveryCount();
    }

    public String activeExecutionState() {
        return taskRuntime.activeExecutionState();
    }

    /**
     * 自动化扰动通过统一运行时取消原任务。
     */
    public void cancelActiveTaskForExperiment(ServerPlayer actor, String reason) {
        taskRuntime.cancel(actor, reason);
    }

    /**
     * 返回客户端可见的有效行为模式。
     */
    public PartnerMode getMode() {
        int ordinal = entityData.get(DATA_MODE);
        PartnerMode[] values = PartnerMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PartnerMode.IDLE;
    }

    /**
     * 仅供行为控制器更新同步投影，业务代码不得直接切换模式。
     */
    public void syncBehaviorMode(PartnerMode mode) {
        entityData.set(DATA_MODE, mode.ordinal());
    }

    public boolean isFollowing() {
        return taskRuntime.isFollowing();
    }

    public Optional<TaskContract> getCurrentContract() {
        return taskRuntime.currentContract();
    }

    public boolean canUseAmbientMovement() {
        return taskRuntime.canUseAmbientMovement();
    }

    public boolean isInventoryMenuOpen() {
        return behaviorController.isInventoryMenuOpen();
    }

    /**
     * 菜单打开时停止当前路径，但不覆盖任务或指令。
     */
    public void onInventoryMenuOpened(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            behaviorController.setInventoryMenuOpen(true);
        }
    }

    /**
     * 主人关闭菜单后由运行时在下一 tick 重新验证并继续任务。
     */
    public void onInventoryMenuClosed(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            behaviorController.setInventoryMenuOpen(false);
        }
    }

    /**
     * 记录执行器内部状态事件，外围实验日志通过领域事件订阅。
     */
    public void logRuntimeEvent(String event) {
        taskRuntime.logRuntimeEvent(event);
    }

    @Override
    public SimpleContainer getInventory() {
        return inventory;
    }

    /**
     * 统计女仆背包中指定物品数量。
     */
    public int countItem(Item item) {
        int count = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 检查背包是否有至少一个可放入目标物品的槽位。
     */
    public boolean canStore(Item item) {
        return inventory.canAddItem(new ItemStack(item));
    }

    /**
     * 检查背包中的斧类工具前置条件。
     */
    public boolean hasAxe() {
        return inventory.getItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.AXES));
    }

    /**
     * 返回面向玩家的背包汇总。
     */
    public String inventorySummary() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        if (totals.isEmpty()) {
            return "EMPTY";
        }
        return totals.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * 在没有运行任务时把全部内部物品归还主人。
     */
    public int returnInventoryTo(ServerPlayer player) {
        if (taskRuntime.hasRunningContract()) {
            return -1;
        }
        int returned = 0;
        for (ItemStack stack : inventory.removeAllItems()) {
            returned += stack.getCount();
            player.getInventory().placeItemBackInInventory(stack);
        }
        return returned;
    }

    public void resetForExperiment(ServerPlayer actor) {
        resetForExperiment(actor, true);
    }

    /**
     * 清除受控场景状态，并按调用方要求归还或丢弃场景物品。
     */
    public void resetForExperiment(ServerPlayer actor, boolean returnContentsToPlayer) {
        if (level().isClientSide()) {
            throw new IllegalStateException("Experiment reset may only run on the server");
        }
        taskRuntime.resetForExperiment(actor);
        for (ItemStack stack : inventory.removeAllItems()) {
            if (returnContentsToPlayer) {
                actor.getInventory().placeItemBackInInventory(stack);
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
        }) {
            ItemStack equipped = getItemBySlot(slot);
            if (!equipped.isEmpty()) {
                setItemSlot(slot, ItemStack.EMPTY);
                if (returnContentsToPlayer) {
                    actor.getInventory().placeItemBackInInventory(equipped);
                }
            }
        }
        behaviorController.setInventoryMenuOpen(false);
        setHealth(getMaxHealth());
    }

    @Override
    public void tick() {
        super.tick();
        taskRuntime.tick();
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isTame()) {
            if (!level().isClientSide()) {
                tame(player);
                setPersistenceRequired();
                setCustomName(Component.translatable("entity.ai-partner.ai_partner"));
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(Component.translatable("message.ai-partner.bound"));
                }
            }
            return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        if (isOwnedBy(player)) {
            ItemStack heldItem = player.getItemInHand(hand);
            if (player.isShiftKeyDown()) {
                if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.openMenu(this);
                }
                return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
            }
            Equippable equippable = heldItem.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    equipArmorFromPlayer(serverPlayer, heldItem, equippable);
                }
                return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
            }
            if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.translatable("message.ai-partner.mode", getMode().name()));
            }
            return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        return super.mobInteract(player, hand);
    }

    /**
     * 把玩家手中的一件护甲直接装备到对应槽位，旧护甲安全归还玩家。
     */
    private void equipArmorFromPlayer(ServerPlayer player, ItemStack heldItem, Equippable equippable) {
        EquipmentSlot slot = equippable.slot();
        if (!isEquippableInSlot(heldItem, slot)) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.armor_not_compatible"));
            return;
        }
        ItemStack previous = getItemBySlot(slot);
        if (!previous.isEmpty()
                && !player.isCreative()
                && EnchantmentHelper.has(previous, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.armor_cannot_remove"));
            return;
        }

        ItemStack equipped = heldItem.copyWithCount(1);
        if (!player.getAbilities().instabuild) {
            heldItem.shrink(1);
        }
        setItemSlot(slot, equipped);
        setGuaranteedDrop(slot);
        setPersistenceRequired();
        if (!previous.isEmpty()) {
            player.getInventory().placeItemBackInInventory(previous);
        }
        player.sendSystemMessage(Component.translatable(
                "message.ai-partner.armor_equipped",
                equipped.getHoverName()
        ));
    }

    @Override
    public Component getDisplayName() {
        return hasCustomName() ? getName() : Component.translatable("container.ai-partner.inventory");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return isAlive() && isOwnedBy(player) ? new AiPartnerMenu(containerId, playerInventory, this) : null;
    }

    @Override
    public Integer getScreenOpeningData(ServerPlayer player) {
        return getId();
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack itemStack) {
        return taskRuntime.acceptsPickup(itemStack.getItem()) && inventory.canAddItem(itemStack);
    }

    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity entity) {
        InventoryCarrier.pickUpItem(level, this, this, entity);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            taskRuntime.fail(FailureCode.PARTNER_DIED);
        }
        super.die(source);
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
        super.dropEquipment(level);
        inventory.removeAllItems().forEach(stack -> spawnAtLocation(level, stack));
    }

    @Override
    public boolean isFood(ItemStack itemStack) {
        return false;
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        writeInventoryToTag(output);
        taskRuntime.save(output);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        readInventoryFromTag(input);
        taskRuntime.load(input);
    }
}
