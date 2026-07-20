package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.goal.AiPartnerFollowOwnerGoal;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 服务端权威的女仆伙伴实体；客户端只读取同步模式并负责表现。
 */
public final class AiPartnerEntity extends TamableAnimal implements InventoryCarrier {
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(
            AiPartnerEntity.class,
            EntityDataSerializers.INT
    );

    private @Nullable TaskContract currentContract;
    private final SimpleContainer inventory = new SimpleContainer(18);
    private final CollectBlockExecutor collectBlockExecutor = new CollectBlockExecutor(this);
    private final DepositItemExecutor depositItemExecutor = new DepositItemExecutor(this);
    private int collectInitialTargetCount;
    private int depositMovedCount;
    private String currentSystemVariant = "RULE_BT";

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
        goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(6, new RandomLookAroundGoal(this));
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
        if (!level().isClientSide()
                && getMode() == PartnerMode.COLLECTING
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING) {
            if (!collectBlockExecutor.isRunning()) {
                collectBlockExecutor.restore(currentContract, collectInitialTargetCount);
            }
            collectBlockExecutor.tick((ServerLevel) level());
        }
        if (!level().isClientSide()
                && getMode() == PartnerMode.DEPOSITING
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
            if (player.isShiftKeyDown() && !heldItem.isEmpty()) {
                if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    if (currentContract != null && currentContract.status() == ContractStatus.RUNNING) {
                        serverPlayer.sendSystemMessage(Component.translatable("message.ai-partner.inventory_busy"));
                    } else {
                        transferHeldItem(serverPlayer, heldItem);
                    }
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

    private void transferHeldItem(ServerPlayer player, ItemStack heldItem) {
        int originalCount = heldItem.getCount();
        Component itemName = heldItem.getHoverName();
        ItemStack remainder = inventory.addItem(heldItem.copy());
        int inserted = originalCount - remainder.getCount();
        if (inserted <= 0) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.inventory_full"));
            return;
        }
        if (!player.getAbilities().instabuild) {
            heldItem.shrink(inserted);
        }
        player.sendSystemMessage(Component.translatable(
                "message.ai-partner.item_received",
                inserted,
                itemName
        ));
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
