package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.combat.MaidCombatController;
import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.entity.ai.MaidAi;
import io.github.ozozorz.aipartner.entity.navigation.AiPartnerPathNavigation;
import io.github.ozozorz.aipartner.growth.MaidGrowthController;
import io.github.ozozorz.aipartner.growth.MaidGrowthData;
import io.github.ozozorz.aipartner.growth.MaidGrowthProgression;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.inventory.InventoryCapacity;
import io.github.ozozorz.aipartner.inventory.MaidEquipmentController;
import io.github.ozozorz.aipartner.inventory.MaidInventoryPersistence;
import io.github.ozozorz.aipartner.life.ActivityLocation;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.life.MaidFeedingService;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import io.github.ozozorz.aipartner.life.MaidPickupController;
import io.github.ozozorz.aipartner.registry.ModItemTags;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.skill.MaidSkillSet;
import io.github.ozozorz.aipartner.work.MaidWorkController;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * AI 女仆的实体外壳，负责三种长期模式、技能、装备、交互和持久化。
 */
public final class AiPartnerEntity extends TamableAnimal implements
        InventoryCarrier,
        ContainerUser,
        ExtendedMenuProvider<Integer>,
        RangedAttackMob {
    private static final int CURRENT_DATA_VERSION = 3;
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

    private final SimpleContainer inventory = new SimpleContainer(MaidInventoryPersistence.STORAGE_SLOT_COUNT);
    private final MaidGrowthData growthData = new MaidGrowthData();
    private final MaidGrowthController growthController = new MaidGrowthController(this, growthData);
    private final MaidGameplayConfig gameplayConfig = MaidGameplayConfig.get();
    private final MaidSkillSet skills = MaidSkillSet.create(this);
    private final MaidLifeController lifeController = new MaidLifeController(this, gameplayConfig);
    private final MaidEquipmentController equipmentController = new MaidEquipmentController(this);
    private final MaidCombatController combatController =
            new MaidCombatController(this, lifeController, skills, equipmentController);
    private final MaidWorkController workController = new MaidWorkController(this, lifeController, skills);
    private final MaidPickupController pickupController =
            new MaidPickupController(this, lifeController, growthData, gameplayConfig);
    private final List<ItemStack> pendingMigrationDrops = new ArrayList<>();

    private Activity brainActivity = Activity.IDLE;
    private @Nullable BlockPos openContainerPosition;
    private @Nullable Container openContainer;
    private int appliedGrowthLevel = -1;
    private boolean inventoryMenuOpen;
    private boolean ownershipRegistered;

    public AiPartnerEntity(EntityType<? extends AiPartnerEntity> entityType, Level level) {
        super(entityType, level);
        setCanPickUpLoot(true);
    }

    /**
     * 使用女仆专用 Provider 从存档记忆构造 Brain。
     */
    @Override
    protected Brain<AiPartnerEntity> makeBrain(Brain.Packed packedBrain) {
        return MaidAi.brainProvider().makeBrain(this, packedBrain);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Brain<AiPartnerEntity> getBrain() {
        return (Brain<AiPartnerEntity>) (Brain<?>) super.getBrain();
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        getBrain().tick(level, this);
        MaidAi.updateActivity(this);
        super.customServerAiStep(level);
    }

    /**
     * 定义女仆基础生存、移动和战斗属性。
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
        // 女仆的自主行为全部由 Brain 活动和技能工作系统驱动。
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new AiPartnerPathNavigation(this, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, PartnerMode.STAY.ordinal());
        builder.define(DATA_SPEECH_BUBBLE, Optional.empty());
        builder.define(DATA_SPEECH_BUBBLE_UNTIL, 0L);
        builder.define(DATA_SKIN_HASH, "");
    }

    public PartnerMode getMode() {
        int ordinal = entityData.get(DATA_MODE);
        PartnerMode[] values = PartnerMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PartnerMode.STAY;
    }

    /**
     * 服务端直接切换长期模式，并同步原版坐下状态与生活边界。
     */
    public void setMode(PartnerMode mode) {
        PartnerMode next = java.util.Objects.requireNonNull(mode, "mode");
        if (getMode() == next) {
            setOrderedToSit(next == PartnerMode.STAY);
            return;
        }
        entityData.set(DATA_MODE, next.ordinal());
        setOrderedToSit(next == PartnerMode.STAY);
        lifeController.onModeChanged(next);
        getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        getNavigation().stop();
    }

    public boolean isFollowing() {
        return getMode() == PartnerMode.FOLLOW;
    }

    public boolean canUseAmbientMovement() {
        return lifeController.canUseAmbientMovement()
                && (workController.mode() == MaidWorkMode.NONE || !lifeController.canPerformScheduledWork());
    }

    public boolean isWorkControllerActive() {
        return workController.controlsMovement();
    }

    public boolean isInventoryMenuOpen() {
        return inventoryMenuOpen;
    }

    public Optional<BlockPos> getActivityNavigationTarget() {
        return lifeController.movementTarget();
    }

    public void requestReturnHome(ServerPlayer actor) {
        if (isOwnedBy(actor)) {
            setMode(PartnerMode.WORK);
            lifeController.requestReturnHome();
        }
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

    /**
     * 选择 WORK 模式使用的技能组合；NONE 仅停用具体工作，不改变长期模式。
     */
    public void setWorkMode(MaidWorkMode mode) {
        workController.setMode(mode);
        if (mode != MaidWorkMode.NONE) {
            setMode(PartnerMode.WORK);
        }
    }

    public String getWorkExecutionState() {
        return workController.executionState();
    }

    public MaidSkillSet getSkills() {
        return skills;
    }

    public boolean shouldUseRangedCombat(LivingEntity target) {
        return combatController.shouldUseRangedCombat(target);
    }

    public boolean shouldUseMeleeCombat(LivingEntity target) {
        return combatController.shouldUseMeleeCombat(target);
    }

    public Optional<LivingEntity> selectDefensiveThreat() {
        return combatController.selectThreat();
    }

    public boolean isLegalCombatTarget(@Nullable LivingEntity target) {
        return combatController.isLegalTarget(target);
    }

    public boolean hasActiveCombatTarget() {
        return getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                .filter(LivingEntity::isAlive)
                .isPresent();
    }

    public void acceptBrainCombatTarget(LivingEntity target) {
        setTarget(target);
    }

    /**
     * 原子清除战斗记忆、路径和盾牌使用状态。
     */
    public void clearBrainCombatTarget() {
        combatController.clearCombat();
        getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
    }

    public void updateShieldDefense(LivingEntity target) {
        combatController.updateShieldDefense(target);
    }

    public boolean tryRangedBrainAttack(LivingEntity target, float power) {
        return combatController.performRangedAttack(target, power);
    }

    public boolean tryMeleeBrainAttack(ServerLevel level, LivingEntity target) {
        return combatController.performMeleeAttack(level, target);
    }

    /**
     * Activity 切换只处理瞬时战斗导航，不改变长期模式。
     */
    public void onBrainActivityChanged(Activity activity) {
        boolean changed = activity != brainActivity;
        brainActivity = activity;
        if (activity == Activity.FIGHT) {
            if (changed) {
                getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
                getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                getNavigation().stop();
            }
            getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                    .filter(combatController::isLegalTarget)
                    .ifPresent(this::acceptBrainCombatTarget);
            return;
        }
        if (changed) {
            combatController.clearCombat();
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        combatController.performRangedAttack(target, power);
    }

    /**
     * 菜单打开时只暂停路径，关闭后各控制器会从可验证状态继续。
     */
    public void onInventoryMenuOpened(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            inventoryMenuOpen = true;
            getNavigation().stop();
        }
    }

    public void onInventoryMenuClosed(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            inventoryMenuOpen = false;
        }
    }

    @Override
    public SimpleContainer getInventory() {
        return inventory;
    }

    public int countItem(Item item) {
        int count = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public boolean canStore(Item item) {
        return canStore(item, 1);
    }

    public boolean canStore(Item item, int quantity) {
        return InventoryCapacity.canAccept(inventory, new ItemStack(item), quantity);
    }

    public boolean hasAxe() {
        return getMainHandItem().is(ItemTags.AXES)
                || inventory.getItems().stream().anyMatch(stack -> stack.is(ItemTags.AXES));
    }

    /**
     * 返回包含主副手、护甲与储物区的物品汇总。
     */
    public String inventorySummary() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (EquipmentSlot slot : allEquipmentSlots()) {
            addToSummary(totals, getItemBySlot(slot));
        }
        inventory.getItems().forEach(stack -> addToSummary(totals, stack));
        if (totals.isEmpty()) {
            return "EMPTY";
        }
        return totals.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * 把全部储物、护甲和手持物归还主人。
     */
    public int returnInventoryTo(ServerPlayer player) {
        int returned = 0;
        for (EquipmentSlot slot : allEquipmentSlots()) {
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty()) {
                returned += stack.getCount();
                setItemSlot(slot, ItemStack.EMPTY);
                player.getInventory().placeItemBackInInventory(stack);
            }
        }
        for (ItemStack stack : inventory.removeAllItems()) {
            returned += stack.getCount();
            player.getInventory().placeItemBackInInventory(stack);
        }
        return returned;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            lifeController.tick();
            equipmentController.tick();
            workController.tick();
            pickupController.tick();
            applyGrowthAttributes();
            if (!ownershipRegistered && getOwnerReference() != null) {
                PartnerService.registerLoaded(this);
                ownershipRegistered = true;
            }
            dropPendingMigrationItems();
        }
    }

    /**
     * 未归属女仆仅接受数据包定义的安全食物驯服；其余交互交还原版以支持拴绳。
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (!isTame()) {
            if (!heldItem.is(ModItemTags.MAID_TAMING_FOODS)) {
                return super.mobInteract(player, hand);
            }
            if (!level().isClientSide()) {
                if (!player.hasInfiniteMaterials()) {
                    heldItem.shrink(1);
                }
                playSound(SoundEvents.GENERIC_EAT.value(), 1.0F, 1.0F);
                if (getRandom().nextInt(3) == 0) {
                    tame(player);
                    setPersistenceRequired();
                    setMode(PartnerMode.FOLLOW);
                    initializeLifeAtSpawn();
                    PartnerService.registerLoaded(this);
                    ownershipRegistered = true;
                    level().broadcastEntityEvent(this, (byte) 7);
                    player.sendSystemMessage(Component.translatable("message.ai-partner.tamed"));
                } else {
                    level().broadcastEntityEvent(this, (byte) 6);
                }
            }
            return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }

        if (isOwnedBy(player)) {
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
        if (!player.hasInfiniteMaterials()) {
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
        return (workController.acceptsWorkDrops() || pickupController.wantsItem(itemStack))
                && inventory.canAddItem(itemStack);
    }

    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity entity) {
        if (workController.acceptsWorkDrops() || pickupController.wantsItem(entity)) {
            InventoryCarrier.pickUpItem(level, this, this, entity);
        }
    }

    /**
     * 暴露原版 Mob 的装备比较，以统一护甲、武器、附魔和耐久优先级。
     */
    public boolean isBetterEquipment(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        return canReplaceCurrentItem(candidate, current, slot);
    }

    /**
     * 遵循原版狼的攻击排除规则。
     */
    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        if (target instanceof Creeper || target instanceof Ghast) {
            return false;
        }
        if (target instanceof TamableAnimal tamable) {
            return !tamable.isTame() || tamable.getOwner() != owner;
        }
        if (target instanceof Player player
                && owner instanceof Player ownerPlayer
                && !ownerPlayer.canHarmPlayer(player)) {
            return false;
        }
        return !(target instanceof AbstractHorse horse && horse.isTamed());
    }

    public boolean wantsToAttackLikeWolf(LivingEntity target, LivingEntity owner) {
        return wantsToAttack(target, owner);
    }

    /**
     * 标记容器会话，使原版箱子开合计数能够识别女仆。
     */
    public void beginContainerUse(BlockPos position, Container container) {
        if (openContainer != null) {
            openContainer.stopOpen(this);
        }
        openContainerPosition = position.immutable();
        openContainer = container;
        container.startOpen(this);
    }

    public void endContainerUse(BlockPos position, Container container) {
        if (openContainer == container && position.equals(openContainerPosition)) {
            container.stopOpen(this);
            openContainer = null;
            openContainerPosition = null;
        }
    }

    @Override
    public boolean hasContainerOpen(ContainerOpenersCounter counter, BlockPos position) {
        return openContainer != null && position.equals(openContainerPosition);
    }

    @Override
    public double getContainerInteractionRange() {
        return 4.0;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            closeOpenContainer();
            workController.shutdown();
        }
        super.die(source);
    }

    @Override
    public boolean killedEntity(ServerLevel level, LivingEntity entity, DamageSource source) {
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
        if (!level().isClientSide()) {
            closeOpenContainer();
        }
        super.onRemoval(reason);
        if (!level().isClientSide() && reason.shouldDestroy()) {
            workController.shutdown();
            PartnerService.unregisterDestroyed(this);
        }
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
        for (EquipmentSlot slot : allEquipmentSlots()) {
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
        output.putInt("AiPartnerDataVersion", CURRENT_DATA_VERSION);
        output.putString("MaidMode", getMode().name());
        MaidInventoryPersistence.save(inventory, output);
        lifeController.save(output);
        workController.save(output);
        skills.containerMemory().save(output);
        growthData.save(output);
        growthController.save(output);
        if (!getSkinHash().isEmpty()) {
            output.putString("MaidSkinHash", getSkinHash());
        }
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

        String savedMode = input.getString("MaidMode").orElseGet(() -> {
            String directive = input.getStringOr("ManualDirective", "");
            if ("FOLLOW".equals(directive)) {
                return "FOLLOW";
            }
            if ("STAY".equals(directive)) {
                return "STAY";
            }
            return input.getStringOr("PartnerMode", "WORK");
        });
        PartnerMode restoredMode = PartnerMode.fromSavedName(savedMode);
        entityData.set(DATA_MODE, restoredMode.ordinal());
        setOrderedToSit(restoredMode == PartnerMode.STAY);

        lifeController.load(input);
        workController.load(input);
        skills.containerMemory().load(input);
        growthData.load(input);
        growthController.load(input);
        appliedGrowthLevel = -1;
        inventoryMenuOpen = false;
        setSkinHash(input.getStringOr("MaidSkinHash", ""));
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

    public void rewardFoodAffection(int amount, int cooldownTicks) {
        growthController.rewardFood(amount, cooldownTicks);
    }

    public void rewardWorkCompletion(MaidWorkMode mode) {
        growthController.rewardWork(mode);
    }

    public int adjustWorkCooldown(int baseTicks) {
        return MaidGrowthProgression.adjustCooldown(baseTicks, getGrowthLevel());
    }

    public int getWorkPathRetryLimit(int baseRetries) {
        return baseRetries + MaidGrowthProgression.effectsForLevel(getGrowthLevel()).extraPathRetries();
    }

    /**
     * 把成长等级投影到原版实体属性。
     */
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

    public void syncGrowthData() {
        // ContainerData 每 tick 从实体读取，无需额外数据包。
    }

    /**
     * 显示生活反馈气泡；这不是已删除的 R 键本地对话系统。
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

    /**
     * 空哈希使用模组内置女仆皮肤；非空值仅接受服务端验证的 SHA-256 标识。
     */
    public void setSkinHash(String hash) {
        String normalized = hash == null ? "" : hash.toLowerCase(java.util.Locale.ROOT);
        entityData.set(DATA_SKIN_HASH, normalized.matches("[0-9a-f]{64}") ? normalized : "");
    }

    private void closeOpenContainer() {
        if (openContainer != null) {
            openContainer.stopOpen(this);
            openContainer = null;
            openContainerPosition = null;
        }
    }

    private static EquipmentSlot[] allEquipmentSlots() {
        return new EquipmentSlot[]{
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
        };
    }

    private static void addToSummary(Map<String, Integer> totals, ItemStack stack) {
        if (!stack.isEmpty()) {
            totals.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    Integer::sum
            );
        }
    }

    private void dropPendingMigrationItems() {
        if (!(level() instanceof ServerLevel level) || pendingMigrationDrops.isEmpty()) {
            return;
        }
        for (ItemStack stack : List.copyOf(pendingMigrationDrops)) {
            spawnAtLocation(level, stack);
        }
        pendingMigrationDrops.clear();
    }
}
