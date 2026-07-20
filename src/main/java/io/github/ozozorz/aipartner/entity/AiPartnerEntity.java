package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerFollowOwnerGoal;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerIdleWanderGoal;
import io.github.ozozorz.aipartner.entity.navigation.AiPartnerPathNavigation;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import io.github.ozozorz.aipartner.executor.CollectAndDepositExecutor;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.TamableAnimal;
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
 * 服务端权威的女仆伙伴实体；客户端只读取同步模式并负责表现。
 */
public final class AiPartnerEntity extends TamableAnimal implements InventoryCarrier, ExtendedMenuProvider<Integer> {
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.INT
    );

    private @Nullable TaskContract currentContract;
    private final SimpleContainer inventory = new SimpleContainer(AiPartnerMenu.STORAGE_SLOT_COUNT);
    private final CollectBlockExecutor collectBlockExecutor = new CollectBlockExecutor(this);
    private final DepositItemExecutor depositItemExecutor = new DepositItemExecutor(this);
    private final CollectAndDepositExecutor collectAndDepositExecutor = new CollectAndDepositExecutor(
            this,
            collectBlockExecutor,
            depositItemExecutor
    );
    private int collectInitialTargetCount;
    private int depositMovedCount;
    private CollectAndDepositExecutor.Phase compositePhase = CollectAndDepositExecutor.Phase.IDLE;
    private String currentSystemVariant = "RULE_BT";
    private boolean inventoryMenuOpen;

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
     * 使用支持开门、过门和漂浮的核心导航器，供跟随与所有确定性任务共用。
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
     * 接收唯一活动契约并立即启动对应的确定性执行模式。
     */
    public void applyContract(TaskContract contract, ServerPlayer actor, String rawInstruction) {
        applyContract(contract, actor, rawInstruction, "RULE_BT");
    }

    /**
     * 接收带实验系统变体标识的契约，供 LLM/IBC 与规则基线共用执行器。
     */
    public void applyContract(
            TaskContract contract,
            ServerPlayer actor,
            String rawInstruction,
            String systemVariant
    ) {
        if (level().isClientSide()) {
            throw new IllegalStateException("Contracts may only be applied on the server");
        }
        String nextSystemVariant = systemVariant == null || systemVariant.isBlank() ? "UNKNOWN" : systemVariant;

        if (contract.job().type() == JobType.CANCEL) {
            cancelExistingContract(actor, rawInstruction);
            currentSystemVariant = nextSystemVariant;
            currentContract = contract;
            logEvent("contract_accepted", actor, rawInstruction);
            contract.markRunning();
            setMode(PartnerMode.IDLE);
            navigation.stop();
            logEvent("contract_running", actor, rawInstruction);
            // CANCEL 的目标谓词在导航停止且旧任务终止后立即成立。
            contract.markCompleted();
            logEvent("contract_completed", actor, rawInstruction);
            return;
        }

        cancelExistingContract(actor, "replaced_by_new_contract");
        currentSystemVariant = nextSystemVariant;
        currentContract = contract;
        logEvent("contract_accepted", actor, rawInstruction);
        contract.markRunning();
        switch (contract.job().type()) {
            case FOLLOW -> setMode(PartnerMode.FOLLOWING);
            case STAY -> {
                setMode(PartnerMode.STAYING);
                navigation.stop();
            }
            case COLLECT_BLOCK -> {
                setMode(PartnerMode.COLLECTING);
                collectBlockExecutor.start(contract);
                collectInitialTargetCount = collectBlockExecutor.initialTargetCount();
            }
            case DEPOSIT_ITEM -> {
                setMode(PartnerMode.DEPOSITING);
                depositItemExecutor.start(contract);
                depositMovedCount = 0;
            }
            case COLLECT_AND_DEPOSIT -> {
                depositMovedCount = 0;
                collectAndDepositExecutor.start(contract);
                collectInitialTargetCount = collectBlockExecutor.initialTargetCount();
            }
            default -> throw new IllegalStateException("Contract reached executor without an implementation: " + contract.job().type());
        }
        logEvent("contract_running", actor, rawInstruction);
    }

    /**
     * 由运行时监控器以类型化原因结束当前任务。
     */
    public void failActiveContract(FailureCode failureCode) {
        if (currentContract == null || currentContract.status().isTerminal()) {
            return;
        }
        currentContract.markFailed(failureCode);
        setMode(PartnerMode.IDLE);
        navigation.stop();
        collectBlockExecutor.stop();
        depositItemExecutor.stop();
        collectAndDepositExecutor.stop();
        logEvent("contract_failed", null, "runtime_monitor");
        notifyOwner(Component.translatable("message.ai-partner.failed", failureCode.name()));
    }

    /**
     * 返回契约允许的本地恢复次数。
     */
    public int getMaximumLocalRetries() {
        return currentContract == null ? 0 : currentContract.failurePolicy().maxLocalRetries();
    }

    /**
     * 返回实体当前同步行为模式。
     */
    public PartnerMode getMode() {
        int ordinal = entityData.get(DATA_MODE);
        PartnerMode[] values = PartnerMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PartnerMode.IDLE;
    }

    /**
     * 判断 FOLLOW 状态机是否正在运行。
     */
    public boolean isFollowing() {
        return getMode() == PartnerMode.FOLLOWING
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING;
    }

    public Optional<TaskContract> getCurrentContract() {
        return Optional.ofNullable(currentContract);
    }

    /**
     * 判断当前是否允许空闲漫步；工作、待命、跟随或打开背包时均不会随机移动。
     */
    public boolean canUseAmbientMovement() {
        return !inventoryMenuOpen
                && getMode() == PartnerMode.IDLE
                && (currentContract == null || currentContract.status().isTerminal());
    }

    /**
     * 返回主人是否正在操作女仆背包，用于暂停导航而不改变任务契约。
     */
    public boolean isInventoryMenuOpen() {
        return inventoryMenuOpen;
    }

    /**
     * 菜单打开时停止当前路径，防止女仆走出玩家可交互范围。
     */
    public void onInventoryMenuOpened(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            inventoryMenuOpen = true;
            navigation.stop();
        }
    }

    /**
     * 主人关闭菜单后恢复由当前模式决定的行为调度。
     */
    public void onInventoryMenuClosed(Player player) {
        if (!level().isClientSide() && isOwnedBy(player)) {
            inventoryMenuOpen = false;
        }
    }

    /**
     * 由执行器在目标谓词已经成立后完成当前契约。
     */
    public void completeActiveContract() {
        if (currentContract == null || currentContract.status() != ContractStatus.RUNNING) {
            return;
        }
        currentContract.markCompleted();
        setMode(PartnerMode.IDLE);
        navigation.stop();
        collectBlockExecutor.stop();
        depositItemExecutor.stop();
        collectAndDepositExecutor.stop();
        logEvent("contract_completed", null, "goal_predicate_satisfied");
        notifyOwner(Component.translatable(
                "message.ai-partner.completed",
                currentContract.job().type().name(),
                currentContract.job().quantity(),
                currentContract.job().target()
        ));
    }

    /**
     * 记录确定性执行器的状态转换，供失败分析使用。
     */
    public void logRuntimeEvent(String event) {
        logEvent(event, null, "runtime_executor");
    }

    @Override
    public SimpleContainer getInventory() {
        return inventory;
    }

    /**
     * 统计女仆背包中指定物品数量，作为目标谓词输入。
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
     * 保存存箱执行器的增量进度，供实体持久化和重启恢复使用。
     */
    public void updateDepositProgress(int movedCount) {
        depositMovedCount = Math.max(0, movedCount);
    }

    /**
     * 保存采集阶段的背包基线，供组合任务跨服务器重启恢复。
     */
    public void updateCollectProgressBaseline(int initialTargetCount) {
        collectInitialTargetCount = Math.max(0, initialTargetCount);
    }

    /**
     * 同步组合任务的持久化阶段和客户端行为模式。
     */
    public void onCompositePhaseChanged(CollectAndDepositExecutor.Phase nextPhase) {
        compositePhase = nextPhase;
        switch (nextPhase) {
            case COLLECTING -> setMode(PartnerMode.COLLECTING);
            case DEPOSITING -> setMode(PartnerMode.DEPOSITING);
            case IDLE, COMPLETE, FAILED -> setMode(PartnerMode.IDLE);
        }
    }

    /**
     * 返回面向玩家的背包汇总，不暴露物品 NBT 或内部槽位实现。
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
     * 在没有运行任务时把全部内部物品归还主人；玩家背包满时由原版逻辑掉落在玩家附近。
     */
    public int returnInventoryTo(ServerPlayer player) {
        if (currentContract != null && currentContract.status() == ContractStatus.RUNNING) {
            return -1;
        }
        int returned = 0;
        for (ItemStack stack : inventory.removeAllItems()) {
            returned += stack.getCount();
            player.getInventory().placeItemBackInInventory(stack);
        }
        return returned;
    }

    /**
     * 为受控实验场景清除任务状态，并先把原背包和装备安全归还管理员玩家。
     */
    public void resetForExperiment(ServerPlayer actor) {
        if (level().isClientSide()) {
            throw new IllegalStateException("Experiment reset may only run on the server");
        }
        cancelExistingContract(actor, "experiment_reset");
        currentContract = null;
        collectAndDepositExecutor.stop();
        collectBlockExecutor.stop();
        depositItemExecutor.stop();
        navigation.stop();
        for (ItemStack stack : inventory.removeAllItems()) {
            actor.getInventory().placeItemBackInInventory(stack);
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
                actor.getInventory().placeItemBackInInventory(equipped);
            }
        }
        collectInitialTargetCount = 0;
        depositMovedCount = 0;
        compositePhase = CollectAndDepositExecutor.Phase.IDLE;
        currentSystemVariant = "EXPERIMENT_RESET";
        inventoryMenuOpen = false;
        setMode(PartnerMode.IDLE);
        setHealth(getMaxHealth());
    }

    private void setMode(PartnerMode mode) {
        entityData.set(DATA_MODE, mode.ordinal());
    }

    private void cancelExistingContract(@Nullable ServerPlayer actor, String reason) {
        if (currentContract == null || currentContract.status().isTerminal()) {
            return;
        }
        currentContract.markCancelled();
        setMode(PartnerMode.IDLE);
        navigation.stop();
        collectBlockExecutor.stop();
        depositItemExecutor.stop();
        collectAndDepositExecutor.stop();
        logEvent("contract_cancelled", actor, reason);
    }

    private void logEvent(String event, @Nullable ServerPlayer actor, String rawInstruction) {
        if (currentContract != null) {
            ExperimentLogger.getInstance().logContractEvent(
                    event,
                    currentSystemVariant,
                    this,
                    actor,
                    currentContract,
                    rawInstruction
            );
        }
    }

    private void notifyOwner(Component message) {
        if (getOwner() instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && inventoryMenuOpen) {
            if (isCompositeContractRunning()) {
                collectAndDepositExecutor.pauseForMenuTick();
            } else {
                collectBlockExecutor.pauseForMenuTick();
                depositItemExecutor.pauseForMenuTick();
            }
            return;
        }
        if (!level().isClientSide() && isCompositeContractRunning()) {
            if (!collectAndDepositExecutor.isRunning()) {
                collectAndDepositExecutor.restore(
                        currentContract,
                        compositePhase,
                        collectInitialTargetCount,
                        depositMovedCount
                );
            }
            collectAndDepositExecutor.tick((ServerLevel) level());
        }
        if (!level().isClientSide()
                && getMode() == PartnerMode.COLLECTING
                && !isCompositeContractRunning()
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING) {
            if (!collectBlockExecutor.isRunning()) {
                collectBlockExecutor.restore(currentContract, collectInitialTargetCount);
            }
            collectBlockExecutor.tick((ServerLevel) level());
        }
        if (!level().isClientSide()
                && getMode() == PartnerMode.DEPOSITING
                && !isCompositeContractRunning()
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING) {
            if (!depositItemExecutor.isRunning()) {
                depositItemExecutor.restore(currentContract, depositMovedCount);
            }
            depositItemExecutor.tick((ServerLevel) level());
        }
        if (!level().isClientSide() && isFollowing() && tickCount % 100 == 0 && getOwner() == null) {
            failActiveContract(FailureCode.OWNER_OFFLINE);
        }
    }

    private boolean isCompositeContractRunning() {
        return currentContract != null
                && currentContract.job().type() == JobType.COLLECT_AND_DEPOSIT
                && currentContract.status() == ContractStatus.RUNNING;
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
            if (!level().isClientSide()) {
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(Component.translatable("message.ai-partner.mode", getMode().name()));
                }
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

    /**
     * 仅允许主人创建连接真实实体物品栏的服务端菜单。
     */
    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return isAlive() && isOwnedBy(player) ? new AiPartnerMenu(containerId, playerInventory, this) : null;
    }

    /**
     * 把实体编号发送给客户端菜单工厂，以解析要展示的具体女仆。
     */
    @Override
    public Integer getScreenOpeningData(ServerPlayer player) {
        return getId();
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack itemStack) {
        return collectBlockExecutor.accepts(itemStack.getItem()) && inventory.canAddItem(itemStack);
    }

    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity entity) {
        InventoryCarrier.pickUpItem(level, this, this, entity);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            failActiveContract(FailureCode.PARTNER_DIED);
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
        output.putString("PartnerMode", getMode().name());
        writeInventoryToTag(output);
        output.putInt("CollectInitialTargetCount", collectInitialTargetCount);
        output.putInt("DepositMovedCount", depositMovedCount);
        output.putString("CompositePhase", compositePhase.name());
        output.putString("CurrentSystemVariant", currentSystemVariant);
        if (currentContract != null) {
            output.putString("ContractId", currentContract.contractId().toString());
            output.putString("ContractJobType", currentContract.job().type().name());
            output.putString("ContractTarget", currentContract.job().target());
            output.putInt("ContractQuantity", currentContract.job().quantity());
            output.putInt("ContractRadius", currentContract.job().radius());
            output.putString("ContractStatus", currentContract.status().name());
            output.putString("ContractFailureCode", currentContract.failureCode().name());
            output.putLong("ContractAcceptedAt", currentContract.acceptedAtEpochMillis());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        readInventoryFromTag(input);
        collectInitialTargetCount = input.getIntOr("CollectInitialTargetCount", 0);
        depositMovedCount = input.getIntOr("DepositMovedCount", 0);
        compositePhase = CollectAndDepositExecutor.Phase.fromName(input.getStringOr(
                "CompositePhase",
                CollectAndDepositExecutor.Phase.COLLECTING.name()
        ));
        currentSystemVariant = input.getStringOr("CurrentSystemVariant", "RULE_BT");
        setMode(PartnerMode.fromName(input.getStringOr("PartnerMode", PartnerMode.IDLE.name())));
        Optional<String> savedContractId = input.getString("ContractId");
        if (savedContractId.isEmpty()) {
            currentContract = null;
            setMode(PartnerMode.IDLE);
            return;
        }

        try {
            JobSpec job = new JobSpec(
                    JobType.valueOf(input.getStringOr("ContractJobType", JobType.CANCEL.name())),
                    input.getStringOr("ContractTarget", ""),
                    input.getIntOr("ContractQuantity", 0),
                    input.getIntOr("ContractRadius", 0)
            );
            currentContract = TaskContract.restored(
                    UUID.fromString(savedContractId.get()),
                    job,
                    input.getLongOr("ContractAcceptedAt", System.currentTimeMillis()),
                    ContractStatus.valueOf(input.getStringOr("ContractStatus", ContractStatus.FAILED.name())),
                    FailureCode.valueOf(input.getStringOr("ContractFailureCode", FailureCode.INTERNAL_ERROR.name()))
            );
            if (currentContract.status().isTerminal()) {
                setMode(PartnerMode.IDLE);
            }
        } catch (IllegalArgumentException exception) {
            currentContract = null;
            setMode(PartnerMode.IDLE);
        }
    }
}
