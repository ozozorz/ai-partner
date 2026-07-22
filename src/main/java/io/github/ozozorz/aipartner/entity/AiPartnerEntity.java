package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.combat.MaidCombatController;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.core.task.MaidTaskRuntime;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.control.MaidDriverSettings;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerFollowOwnerGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerIdleWanderGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerMeleeCombatGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerRangedCombatGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerReturnToActivityGoal;
import io.github.ozozorz.aipartner.entity.navigation.AiPartnerPathNavigation;
import io.github.ozozorz.aipartner.growth.MaidGrowthData;
import io.github.ozozorz.aipartner.growth.MaidGrowthController;
import io.github.ozozorz.aipartner.growth.MaidGrowthProgression;
import io.github.ozozorz.aipartner.inventory.MaidInventoryPersistence;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.life.ActivityLocation;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.life.MaidFeedingService;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import io.github.ozozorz.aipartner.life.MaidPickupController;
import io.github.ozozorz.aipartner.registry.ModTasks;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.work.MaidWorkController;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.entity.monster.RangedAttackMob;
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
public final class AiPartnerEntity extends TamableAnimal implements InventoryCarrier, ExtendedMenuProvider<Integer>, RangedAttackMob {
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<Optional<Component>> DATA_SPEECH_BUBBLE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.OPTIONAL_COMPONENT
    );
    private static final EntityDataAccessor<Long> DATA_SPEECH_BUBBLE_UNTIL = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.LONG
    );
    private static final EntityDataAccessor<String> DATA_SKIN_HASH = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.STRING
    );
    private static final EntityDataAccessor<Integer> DATA_DRIVE_MODE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<String> DATA_LLM_API_KEY_ENV = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.STRING
    );

    private final SimpleContainer inventory = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
    private final MaidGrowthData growthData = new MaidGrowthData();
    private final MaidGrowthController growthController = new MaidGrowthController(this, growthData);
    private final MaidBehaviorController behaviorController = new MaidBehaviorController(this);
    private final MaidTaskRuntime taskRuntime = new MaidTaskRuntime(
            this,
            behaviorController,
            ModTasks.createRegistry()
    );
    private final MaidGameplayConfig gameplayConfig = MaidGameplayConfig.get();
    private final MaidLifeController lifeController = new MaidLifeController(
            this,
            behaviorController,
            taskRuntime,
            gameplayConfig
    );
    private final MaidCombatController combatController = new MaidCombatController(
            this,
            behaviorController,
            lifeController
    );
    private final MaidWorkController workController = new MaidWorkController(this, lifeController);
    private final MaidPickupController pickupController = new MaidPickupController(
            this,
            lifeController,
            growthData,
            gameplayConfig
    );
    private final List<ItemStack> pendingMigrationDrops = new ArrayList<>();
    private int appliedGrowthLevel = -1;
    private boolean ownershipRegistered;

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
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new AiPartnerRangedCombatGoal(this));
        goalSelector.addGoal(2, new AiPartnerMeleeCombatGoal(this));
        goalSelector.addGoal(3, new AiPartnerFollowOwnerGoal(this, 1.05, 5.0F, 3.0F));
        goalSelector.addGoal(4, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new AiPartnerReturnToActivityGoal(this, 0.9));
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
        builder.define(DATA_SPEECH_BUBBLE, Optional.empty());
        builder.define(DATA_SPEECH_BUBBLE_UNTIL, 0L);
        builder.define(DATA_SKIN_HASH, "");
        builder.define(DATA_DRIVE_MODE, MaidDriveMode.LOCAL.ordinal());
        builder.define(DATA_LLM_API_KEY_ENV, defaultApiKeyEnvironmentVariable());
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
        return taskRuntime.canUseAmbientMovement()
                && lifeController.canUseAmbientMovement()
                && (workController.mode() == MaidWorkMode.NONE || !lifeController.canPerformScheduledWork());
    }

    public boolean isInventoryMenuOpen() {
        return behaviorController.isInventoryMenuOpen();
    }

    public Optional<net.minecraft.core.BlockPos> getActivityNavigationTarget() {
        return lifeController.movementTarget();
    }

    /**
     * 任务运行时切换长期指令时通知生活层建立或解除位置锚点。
     */
    public void onManualDirectiveActivated(ManualDirective directive) {
        lifeController.onManualDirectiveActivated(directive);
    }

    public void requestReturnHome(ServerPlayer actor) {
        taskRuntime.activateManualDirective(ManualDirective.RETURN_HOME, actor, "return_home");
    }

    public void initializeLifeAtSpawn() {
        lifeController.initializeDefaultHome();
    }

    public ScheduleType getScheduleType() {
        return lifeController.scheduleType();
    }

    public void setScheduleType(ScheduleType scheduleType) {
        lifeController.setScheduleType(scheduleType);
    }

    public ScheduleActivity getScheduleActivity() {
        return lifeController.currentActivity();
    }

    public int getTicksUntilScheduleTransition() {
        return lifeController.ticksUntilNextScheduleTransition();
    }

    public boolean isHomeBound() {
        return lifeController.homeBound();
    }

    public void setHomeBound(boolean homeBound) {
        lifeController.setHomeBound(homeBound);
    }

    public int getActivityRadius() {
        return lifeController.activityRadius();
    }

    public void setActivityRadius(int radius) {
        lifeController.setActivityRadius(radius);
    }

    public int getConfiguredLocationMask() {
        return lifeController.configuredLocationMask();
    }

    public void setActivityLocation(ActivityLocationType type) {
        lifeController.setLocationAtCurrentPosition(type);
    }

    public void clearActivityLocation(ActivityLocationType type) {
        lifeController.clearLocation(type);
    }

    public Optional<ActivityLocation> getActivityLocation(ActivityLocationType type) {
        return lifeController.location(type);
    }

    public MaidWorkMode getWorkMode() {
        return workController.mode();
    }

    public void setWorkMode(MaidWorkMode mode) {
        workController.setMode(mode);
    }

    public void cycleWorkMode() {
        workController.cycleMode();
    }

    public String getWorkExecutionState() {
        return workController.executionState();
    }

    public CombatPolicy getCombatPolicy() {
        return combatController.policy();
    }

    public void setCombatPolicy(CombatPolicy policy) {
        combatController.setPolicy(policy);
    }

    public void cycleCombatPolicy() {
        combatController.cyclePolicy();
    }

    public boolean shouldUseRangedCombat() {
        return combatController.shouldUseRangedCombat();
    }

    public boolean shouldUseMeleeCombat() {
        return combatController.shouldUseMeleeCombat();
    }

    @Override
    public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float power) {
        combatController.performRangedAttack(target, power);
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
        return getMainHandItem().typeHolder().is(ItemTags.AXES)
                || inventory.getItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.AXES));
    }

    /**
     * 返回面向玩家的背包汇总。
     */
    public String inventorySummary() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        if (!getMainHandItem().isEmpty()) {
            totals.merge(
                    BuiltInRegistries.ITEM.getKey(getMainHandItem().getItem()).toString(),
                    getMainHandItem().getCount(),
                    Integer::sum
            );
        }
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
        ItemStack mainHand = getMainHandItem();
        if (!mainHand.isEmpty()) {
            returned += mainHand.getCount();
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.getInventory().placeItemBackInInventory(mainHand);
        }
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
        workController.setMode(MaidWorkMode.NONE);
        combatController.setPolicy(CombatPolicy.OFF);
        setHealth(getMaxHealth());
    }

    @Override
    public void tick() {
        super.tick();
        combatController.tick();
        taskRuntime.tick();
        lifeController.tick();
        workController.tick();
        pickupController.tick();
        if (!level().isClientSide()) {
            applyGrowthAttributes();
            if (!ownershipRegistered && getOwnerReference() != null) {
                PartnerService.registerLoaded(this);
                ownershipRegistered = true;
            }
            dropPendingMigrationItems();
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isTame()) {
            if (!level().isClientSide()) {
                if (!(player instanceof ServerPlayer serverPlayer) || !PartnerService.mayOwnAnother(serverPlayer)) {
                    player.sendSystemMessage(Component.translatable(
                            "message.ai-partner.owner_limit",
                            MaidGameplayConfig.get().maxMaidsPerOwner()
                    ));
                    return InteractionResult.FAIL;
                }
                tame(player);
                setPersistenceRequired();
                initializeLifeAtSpawn();
                PartnerService.registerLoaded(this);
                ownershipRegistered = true;
                serverPlayer.sendSystemMessage(Component.translatable("message.ai-partner.bound"));
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
            if (MaidFeedingService.isEdible(heldItem)) {
                if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    MaidFeedingService.feed(this, serverPlayer, heldItem);
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
        return (taskRuntime.acceptsPickup(itemStack.getItem()) || pickupController.wantsItem(itemStack))
                && inventory.canAddItem(itemStack);
    }

    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity entity) {
        if (taskRuntime.acceptsPickup(entity.getItem().getItem()) || pickupController.wantsItem(entity)) {
            InventoryCarrier.pickUpItem(level, this, this, entity);
        }
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            taskRuntime.fail(FailureCode.PARTNER_DIED);
            workController.shutdown();
        }
        super.die(source);
    }

    /** 合法击杀通过原版死亡回调发放有每日上限的成长和好感奖励。 */
    @Override
    public boolean killedEntity(ServerLevel level, net.minecraft.world.entity.LivingEntity entity, DamageSource source) {
        boolean handled = super.killedEntity(level, entity, source);
        if (handled && entity != this) {
            growthController.rewardCombatKill();
        }
        return handled;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            lifeController.onHurt();
        }
        return hurt;
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        if (!level().isClientSide() && reason.shouldDestroy()) {
            workController.shutdown();
            PartnerService.unregisterDestroyed(this);
        }
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
        }) {
            if (!getItemBySlot(slot).isEmpty()) {
                setGuaranteedDrop(slot);
            }
        }
        super.dropEquipment(level);
        inventory.removeAllItems().forEach(stack -> spawnAtLocation(level, stack));
    }

    @Override
    public boolean isFood(ItemStack itemStack) {
        return MaidFeedingService.isEdible(itemStack);
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        MaidInventoryPersistence.save(inventory, output);
        taskRuntime.save(output);
        lifeController.save(output);
        workController.save(output);
        combatController.save(output);
        growthData.save(output);
        growthController.save(output);
        if (!getSkinHash().isEmpty()) {
            output.putString("MaidSkinHash", getSkinHash());
        }
        output.putString("MaidDriveMode", getDriveMode().serializedName());
        output.putString("MaidLlmApiKeyEnvironmentVariable", getLlmApiKeyEnvironmentVariable());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        int dataVersion = input.getIntOr("AiPartnerDataVersion", 0);
        MaidInventoryPersistence.LoadResult inventoryLoad = MaidInventoryPersistence.load(
                inventory,
                getMainHandItem(),
                input,
                dataVersion
        );
        setItemSlot(EquipmentSlot.MAINHAND, inventoryLoad.mainHand());
        pendingMigrationDrops.clear();
        pendingMigrationDrops.addAll(inventoryLoad.overflow());
        taskRuntime.load(input);
        lifeController.load(input);
        workController.load(input);
        combatController.load(input);
        growthData.load(input);
        growthController.load(input);
        appliedGrowthLevel = -1;
        setSkinHash(input.getStringOr("MaidSkinHash", ""));
        setDriveMode(MaidDriveMode.fromSavedName(input.getStringOr("MaidDriveMode", "local")));
        String environmentVariable = input.getStringOr(
                "MaidLlmApiKeyEnvironmentVariable",
                defaultApiKeyEnvironmentVariable()
        );
        setLlmApiKeyEnvironmentVariable(MaidDriverSettings.isValidEnvironmentVariableName(environmentVariable)
                ? environmentVariable
                : defaultApiKeyEnvironmentVariable());
        ownershipRegistered = false;
    }

    public int getAffection() {
        return growthData.affection();
    }

    public int getGrowthExperience() {
        return growthData.experience();
    }

    public int getGrowthLevel() {
        return growthData.level();
    }

    /**
     * 食物好感度受服务端冷却限制，防止堆叠食物瞬间刷满关系值。
     */
    public void rewardFoodAffection(int amount, int cooldownTicks) {
        growthController.rewardFood(amount, cooldownTicks);
    }

    /** 工作控制器只报告一次已完成动作，奖励冷却和每日上限由成长层统一处理。 */
    public void rewardWorkCompletion(MaidWorkMode mode) {
        growthController.rewardWork(mode);
    }

    public int adjustWorkCooldown(int baseTicks) {
        return MaidGrowthProgression.adjustCooldown(baseTicks, getGrowthLevel());
    }

    public int getWorkPathRetryLimit(int baseRetries) {
        return baseRetries + MaidGrowthProgression.effectsForLevel(getGrowthLevel()).extraPathRetries();
    }

    /** 把当前成长等级投影到原版实体属性；成长永远不作为工作解锁条件。 */
    private void applyGrowthAttributes() {
        int level = getGrowthLevel();
        if (appliedGrowthLevel == level) {
            return;
        }
        MaidGrowthProgression.Effects effects = MaidGrowthProgression.effectsForLevel(level);
        java.util.Objects.requireNonNull(getAttribute(Attributes.MAX_HEALTH)).setBaseValue(effects.maxHealth());
        java.util.Objects.requireNonNull(getAttribute(Attributes.ATTACK_DAMAGE)).setBaseValue(effects.attackDamage());
        java.util.Objects.requireNonNull(getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(effects.movementSpeed());
        if (getHealth() > getMaxHealth()) {
            setHealth(getMaxHealth());
        }
        appliedGrowthLevel = level;
    }

    /**
     * 成长数据目前通过菜单 ContainerData 同步，此钩子保留统一变更入口。
     */
    public void syncGrowthData() {
        // ContainerData 每 tick 从实体读取，无需另发自定义数据包。
    }

    /**
     * 显示短时聊天气泡，并可使用原版内置 Allay 声音作为无需资源包的基础语音反馈。
     */
    public void showSpeechBubble(Component message) {
        if (gameplayConfig.chatBubblesEnabled()) {
            entityData.set(DATA_SPEECH_BUBBLE, Optional.of(message));
            entityData.set(DATA_SPEECH_BUBBLE_UNTIL, level().getGameTime() + 80L);
        }
        if (!level().isClientSide() && gameplayConfig.builtInVoiceEnabled()) {
            playSound(SoundEvents.ALLAY_AMBIENT_WITH_ITEM, 0.65F, 1.15F);
        }
    }

    public Optional<Component> getActiveSpeechBubble() {
        if (level().getGameTime() >= entityData.get(DATA_SPEECH_BUBBLE_UNTIL)) {
            return Optional.empty();
        }
        return entityData.get(DATA_SPEECH_BUBBLE);
    }

    public String getSkinHash() {
        return entityData.get(DATA_SKIN_HASH);
    }

    /** Returns the selected natural-language interpretation driver. */
    public MaidDriveMode getDriveMode() {
        int ordinal = entityData.get(DATA_DRIVE_MODE);
        MaidDriveMode[] modes = MaidDriveMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : MaidDriveMode.LOCAL;
    }

    /** Changes only the high-level interpreter; world actions remain server-authoritative. */
    public void setDriveMode(MaidDriveMode mode) {
        entityData.set(DATA_DRIVE_MODE, java.util.Objects.requireNonNull(mode, "mode").ordinal());
    }

    /** Returns an environment variable name, never the API key stored under that name. */
    public String getLlmApiKeyEnvironmentVariable() {
        return entityData.get(DATA_LLM_API_KEY_ENV);
    }

    /** Persists only a validated environment variable identifier. */
    public void setLlmApiKeyEnvironmentVariable(String variableName) {
        entityData.set(
                DATA_LLM_API_KEY_ENV,
                MaidDriverSettings.requireEnvironmentVariableName(variableName)
        );
    }

    /**
     * 只接受服务端验证器生成的 SHA-256 十六进制标识；空值恢复 Alex 外观。
     */
    public void setSkinHash(String hash) {
        String normalized = hash == null ? "" : hash.toLowerCase(java.util.Locale.ROOT);
        entityData.set(DATA_SKIN_HASH, normalized.matches("[0-9a-f]{64}") ? normalized : "");
    }

    private static String defaultApiKeyEnvironmentVariable() {
        String configured = AiPartnerConfig.get().apiKeyEnvironmentVariable();
        return MaidDriverSettings.isValidEnvironmentVariableName(configured)
                ? configured.strip()
                : "DEEPSEEK_API_KEY";
    }

    private void dropPendingMigrationItems() {
        if (!(level() instanceof ServerLevel level) || pendingMigrationDrops.isEmpty()) {
            return;
        }
        for (ItemStack stack : List.copyOf(pendingMigrationDrops)) {
            spawnAtLocation(level, stack);
        }
        pendingMigrationDrops.clear();
        logRuntimeEvent("inventory_migration_overflow_dropped");
    }
}
