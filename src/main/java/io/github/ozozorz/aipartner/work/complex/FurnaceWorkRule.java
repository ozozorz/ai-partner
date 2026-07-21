package io.github.ozozorz.aipartner.work.complex;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.mixin.AbstractFurnaceBlockEntityAccessor;
import io.github.ozozorz.aipartner.work.MaidWorkContext;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.work.MaidWorkRule;
import io.github.ozozorz.aipartner.work.WorkActionResult;
import io.github.ozozorz.aipartner.work.WorkTarget;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 操作原版熔炉的持久化工作状态机；只租用空熔炉，并对每次玩家改动执行守恒复验。
 */
public final class FurnaceWorkRule implements MaidWorkRule {
    private static final int SEARCH_BUDGET_PER_TICK = 384;
    private static final int MAX_VERTICAL_SEARCH_RADIUS = 6;
    private static final int EMPTY_COOLDOWN_TICKS = 80;
    private static final int BLOCKED_COOLDOWN_TICKS = 100;
    private static final double INTERACTION_DISTANCE_SQUARED = 9.0;
    private static final String PREFIX = "ComplexFurnace";

    private State state = State.SELECT_INPUT;
    private @Nullable Iterator<BlockPos> searchIterator;
    private @Nullable BlockPos furnacePosition;
    private @Nullable UUID leaseId;
    private String savedDimension = "";
    private ItemStack plannedInput = ItemStack.EMPTY;
    private ItemStack plannedFuel = ItemStack.EMPTY;
    private ItemStack plannedResult = ItemStack.EMPTY;
    private int resultPerInput;
    private int cookingTimePerInput;
    private int waitTicks;
    private int stateTicks;
    private int pathFailures;
    private int cooldownTicks;

    @Override
    public MaidWorkMode mode() {
        return MaidWorkMode.SMELTER;
    }

    @Override
    public boolean scansBlocks() {
        return false;
    }

    @Override
    public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
        return false;
    }

    @Override
    public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
        return WorkActionResult.BLOCKED;
    }

    @Override
    public boolean managesOwnExecution() {
        return true;
    }

    /** 熔炼只操作已存在的原版熔炉库存，不受生物破坏规则限制。 */
    @Override
    public boolean requiresMobGriefing() {
        return false;
    }

    @Override
    public String managedExecutionState() {
        return state.name();
    }

    @Override
    public void tickManaged(MaidWorkContext context) {
        if (!savedDimension.isEmpty()
                && !savedDimension.equals(context.level().dimension().identifier().toString())) {
            resetCycle(State.SELECT_INPUT);
        }
        stateTicks++;
        switch (state) {
            case SELECT_INPUT -> selectInput(context);
            case SEARCH_FURNACE -> searchFurnace(context);
            case NAVIGATE -> navigate(context);
            case ACQUIRE_LEASE -> acquireLease(context);
            case INSERT -> insertBatch(context);
            case WAIT -> waitForResult(context);
            case COLLECT -> collectResult(context);
            case COOLDOWN -> tickCooldown();
        }
    }

    private void selectInput(MaidWorkContext context) {
        SmeltPlan plan = findSmeltPlan(context).orElse(null);
        if (plan == null) {
            enterCooldown(EMPTY_COOLDOWN_TICKS, context);
            return;
        }
        plannedInput = plan.input();
        plannedFuel = plan.fuel();
        plannedResult = plan.result();
        resultPerInput = plan.resultPerInput();
        cookingTimePerInput = plan.cookingTimePerInput();
        savedDimension = context.level().dimension().identifier().toString();
        transition(State.SEARCH_FURNACE);
    }

    private Optional<SmeltPlan> findSmeltPlan(MaidWorkContext context) {
        SimpleContainer storage = context.partner().getInventory();
        for (int inputSlot = 0; inputSlot < storage.getContainerSize(); inputSlot++) {
            ItemStack inputStack = storage.getItem(inputSlot);
            if (inputStack.isEmpty()) {
                continue;
            }
            SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
            RecipeHolder<SmeltingRecipe> recipe = context.level().recipeAccess()
                    .getRecipeFor(RecipeType.SMELTING, recipeInput, context.level())
                    .orElse(null);
            if (recipe == null) {
                continue;
            }
            ItemStack singleResult = recipe.value().assemble(recipeInput);
            if (singleResult.isEmpty() || singleResult.getCount() <= 0) {
                continue;
            }
            for (int fuelSlot = 0; fuelSlot < storage.getContainerSize(); fuelSlot++) {
                ItemStack fuelStack = storage.getItem(fuelSlot);
                int burnDuration = context.level().fuelValues().burnDuration(fuelStack);
                if (fuelStack.isEmpty()
                        || burnDuration < recipe.value().cookingTime()
                        || fuelStack.getItem().getCraftingRemainder() != null) {
                    continue;
                }
                int availableInputs = inputStack.getCount() - (inputSlot == fuelSlot ? 1 : 0);
                int fuelCapacity = burnDuration / recipe.value().cookingTime();
                int resultCapacity = singleResult.getMaxStackSize() / singleResult.getCount();
                int batch = Math.min(availableInputs, Math.min(fuelCapacity, resultCapacity));
                if (batch <= 0) {
                    continue;
                }
                return Optional.of(new SmeltPlan(
                        inputStack.copyWithCount(batch),
                        fuelStack.copyWithCount(1),
                        singleResult.copyWithCount(singleResult.getCount() * batch),
                        singleResult.getCount(),
                        recipe.value().cookingTime()
                ));
            }
        }
        return Optional.empty();
    }

    private void searchFurnace(MaidWorkContext context) {
        if (!inventoryStillContainsPlan(context)) {
            resetCycle(State.SELECT_INPUT);
            return;
        }
        if (searchIterator == null) {
            searchIterator = BlockPos.withinManhattan(
                    context.boundary().position(),
                    context.boundary().radius(),
                    Math.min(MAX_VERTICAL_SEARCH_RADIUS, context.boundary().radius()),
                    context.boundary().radius()
            ).iterator();
        }
        int checked = 0;
        while (searchIterator.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
            BlockPos candidate = searchIterator.next();
            if (!context.isLegal(candidate)
                    || !isLoaded(context.level(), candidate)
                    || resolveEmptyFurnace(context, candidate).isEmpty()
                    || !WorkstationLeaseRegistry.isAvailable(
                            context.level().dimension(),
                            candidate,
                            context.partner().getUUID()
                    )) {
                continue;
            }
            furnacePosition = candidate.immutable();
            searchIterator = null;
            transition(State.NAVIGATE);
            return;
        }
        if (searchIterator != null && !searchIterator.hasNext()) {
            searchIterator = null;
            enterCooldown(EMPTY_COOLDOWN_TICKS, context);
        }
    }

    private void navigate(MaidWorkContext context) {
        if (furnacePosition == null || resolveEmptyFurnace(context, furnacePosition).isEmpty()) {
            retryFurnaceSearch();
            return;
        }
        if (context.partner().distanceToSqr(furnacePosition.getCenter()) <= INTERACTION_DISTANCE_SQUARED) {
            context.actions().navigation().stop();
            transition(State.ACQUIRE_LEASE);
            return;
        }
        if (stateTicks % 10 != 1) {
            return;
        }
        boolean started = context.actions().navigation().moveTo(furnacePosition, 0.9);
        pathFailures = started ? 0 : pathFailures + 1;
        if (pathFailures > context.partner().getWorkPathRetryLimit(3)) {
            retryFurnaceSearch();
        }
    }

    private void acquireLease(MaidWorkContext context) {
        if (furnacePosition == null || resolveEmptyFurnace(context, furnacePosition).isEmpty()) {
            retryFurnaceSearch();
            return;
        }
        leaseId = WorkstationLeaseRegistry.acquire(
                context.level().dimension(),
                furnacePosition,
                context.partner().getUUID(),
                leaseId
        ).orElse(null);
        if (leaseId == null) {
            retryFurnaceSearch();
            return;
        }
        transition(State.INSERT);
    }

    private void insertBatch(MaidWorkContext context) {
        FurnaceBlockEntity furnace = resolveEmptyFurnace(context, furnacePosition).orElse(null);
        if (furnace == null || !ensureLease(context) || !inventoryStillContainsPlan(context)) {
            abortCycle(context);
            return;
        }
        ItemStack input = removeExact(context.partner().getInventory(), plannedInput);
        if (input.isEmpty()) {
            abortCycle(context);
            return;
        }
        ItemStack fuel = removeExact(context.partner().getInventory(), plannedFuel);
        if (fuel.isEmpty()) {
            context.actions().inventory().add(input);
            abortCycle(context);
            return;
        }
        furnace.setItem(0, input);
        furnace.setItem(1, fuel);
        furnace.setChanged();
        waitTicks = 0;
        transition(State.WAIT);
    }

    private void waitForResult(MaidWorkContext context) {
        FurnaceBlockEntity furnace = resolveLeasedFurnace(context).orElse(null);
        if (furnace == null || !contentsRespectConservation(furnace)) {
            abortCycle(context);
            return;
        }
        waitTicks++;
        if (furnace.getItem(0).isEmpty()
                && ItemStack.isSameItemSameComponents(furnace.getItem(2), plannedResult)
                && furnace.getItem(2).getCount() == plannedResult.getCount()) {
            transition(State.COLLECT);
            return;
        }
        if (waitTicks > cookingTimePerInput * plannedInput.getCount() + 600) {
            abortCycle(context);
        }
    }

    private void collectResult(MaidWorkContext context) {
        FurnaceBlockEntity furnace = resolveLeasedFurnace(context).orElse(null);
        if (furnace == null
                || !contentsRespectConservation(furnace)
                || !context.actions().inventory().canAdd(plannedResult)) {
            abortCycle(context);
            return;
        }
        ItemStack output = furnace.removeItem(2, plannedResult.getCount());
        if (!ItemStack.isSameItemSameComponents(output, plannedResult)
                || output.getCount() != plannedResult.getCount()
                || !context.actions().inventory().add(output)) {
            if (!output.isEmpty()) {
                furnace.setItem(2, output);
            }
            abortCycle(context);
            return;
        }
        furnace.getRecipesToAwardAndPopExperience(context.level(), Vec3.atCenterOf(furnacePosition));
        ((AbstractFurnaceBlockEntityAccessor) furnace).aiPartner$getRecipesUsed().clear();
        releaseLease(context.level(), context.partner());
        context.partner().rewardWorkCompletion(MaidWorkMode.SMELTER);
        clearPlan();
        enterCooldown(30, context);
    }

    private boolean contentsRespectConservation(FurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack result = furnace.getItem(2);
        if (!input.isEmpty() && !ItemStack.isSameItemSameComponents(input, plannedInput)) {
            return false;
        }
        if (!fuel.isEmpty()
                && (!ItemStack.isSameItemSameComponents(fuel, plannedFuel)
                || fuel.getCount() > plannedFuel.getCount())) {
            return false;
        }
        if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, plannedResult)) {
            return false;
        }
        int resultCount = result.isEmpty() ? 0 : result.getCount();
        int inputCount = input.isEmpty() ? 0 : input.getCount();
        return FurnaceBatchConservation.respects(
                inputCount,
                resultCount,
                plannedInput.getCount(),
                resultPerInput
        );
    }

    private Optional<FurnaceBlockEntity> resolveEmptyFurnace(MaidWorkContext context, @Nullable BlockPos position) {
        if (position == null || !(context.partner().getOwner() instanceof ServerPlayer owner)) {
            return Optional.empty();
        }
        return context.level().getBlockEntity(position) instanceof FurnaceBlockEntity furnace
                && furnace.canOpen(owner)
                && furnace.getItem(0).isEmpty()
                && furnace.getItem(1).isEmpty()
                && furnace.getItem(2).isEmpty()
                ? Optional.of(furnace)
                : Optional.empty();
    }

    private Optional<FurnaceBlockEntity> resolveLeasedFurnace(MaidWorkContext context) {
        if (furnacePosition == null
                || !(context.partner().getOwner() instanceof ServerPlayer owner)
                || !(context.level().getBlockEntity(furnacePosition) instanceof FurnaceBlockEntity furnace)
                || !furnace.canOpen(owner)
                || !ensureLease(context)) {
            return Optional.empty();
        }
        return Optional.of(furnace);
    }

    private boolean ensureLease(MaidWorkContext context) {
        if (furnacePosition == null) {
            return false;
        }
        if (leaseId != null && WorkstationLeaseRegistry.isHeldBy(
                context.level().dimension(),
                furnacePosition,
                context.partner().getUUID(),
                leaseId
        )) {
            return true;
        }
        leaseId = WorkstationLeaseRegistry.acquire(
                context.level().dimension(),
                furnacePosition,
                context.partner().getUUID(),
                leaseId
        ).orElse(null);
        return leaseId != null;
    }

    private boolean inventoryStillContainsPlan(MaidWorkContext context) {
        return containsAtLeast(context.partner().getInventory(), plannedInput)
                && containsAtLeastAfterReservation(
                        context.partner().getInventory(),
                        plannedFuel,
                        plannedInput
                );
    }

    private void tickCooldown() {
        if (--cooldownTicks <= 0) {
            transition(State.SELECT_INPUT);
        }
    }

    private void retryFurnaceSearch() {
        furnacePosition = null;
        searchIterator = null;
        leaseId = null;
        pathFailures = 0;
        transition(State.SEARCH_FURNACE);
    }

    private void abortCycle(MaidWorkContext context) {
        releaseLease(context.level(), context.partner());
        clearPlan();
        enterCooldown(BLOCKED_COOLDOWN_TICKS, context);
    }

    private void enterCooldown(int ticks, MaidWorkContext context) {
        searchIterator = null;
        pathFailures = 0;
        cooldownTicks = context.partner().adjustWorkCooldown(ticks);
        transition(State.COOLDOWN);
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private void clearPlan() {
        furnacePosition = null;
        leaseId = null;
        savedDimension = "";
        plannedInput = ItemStack.EMPTY;
        plannedFuel = ItemStack.EMPTY;
        plannedResult = ItemStack.EMPTY;
        resultPerInput = 0;
        cookingTimePerInput = 0;
        waitTicks = 0;
    }

    private void resetCycle(State next) {
        searchIterator = null;
        pathFailures = 0;
        cooldownTicks = 0;
        clearPlan();
        transition(next);
    }

    @Override
    public void onDeselected(ServerLevel level, AiPartnerEntity partner) {
        if (furnacePosition != null
                && level.getBlockEntity(furnacePosition) instanceof FurnaceBlockEntity furnace
                && contentsRespectConservation(furnace)) {
            safelyReturnSlot(partner, furnace, 0);
            safelyReturnSlot(partner, furnace, 1);
            safelyReturnSlot(partner, furnace, 2);
        }
        releaseLease(level, partner);
        resetCycle(State.SELECT_INPUT);
    }

    private void releaseLease(ServerLevel level, AiPartnerEntity partner) {
        if (furnacePosition != null) {
            WorkstationLeaseRegistry.release(
                    level.dimension(),
                    furnacePosition,
                    partner.getUUID(),
                    leaseId
            );
        }
        leaseId = null;
    }

    private static void safelyReturnSlot(AiPartnerEntity partner, AbstractFurnaceBlockEntity furnace, int slot) {
        ItemStack stack = furnace.getItem(slot);
        if (stack.isEmpty() || !partner.getInventory().canAddItem(stack)) {
            return;
        }
        ItemStack removed = furnace.removeItem(slot, stack.getCount());
        ItemStack remainder = partner.getInventory().addItem(removed);
        if (!remainder.isEmpty()) {
            furnace.setItem(slot, remainder);
        }
    }

    @Override
    public void save(ValueOutput output) {
        output.putString(PREFIX + "State", state.name());
        output.putString(PREFIX + "Dimension", savedDimension);
        if (furnacePosition != null) {
            output.putLong(PREFIX + "Position", furnacePosition.asLong());
            output.putBoolean(PREFIX + "HasPosition", true);
        }
        if (leaseId != null) {
            output.putString(PREFIX + "LeaseId", leaseId.toString());
        }
        if (!plannedInput.isEmpty()) {
            output.store(PREFIX + "Input", ItemStack.CODEC, plannedInput);
            output.store(PREFIX + "Fuel", ItemStack.CODEC, plannedFuel);
            output.store(PREFIX + "Result", ItemStack.CODEC, plannedResult);
        }
        output.putInt(PREFIX + "ResultPerInput", resultPerInput);
        output.putInt(PREFIX + "CookingTimePerInput", cookingTimePerInput);
        output.putInt(PREFIX + "WaitTicks", waitTicks);
        output.putInt(PREFIX + "CooldownTicks", cooldownTicks);
    }

    @Override
    public void load(ValueInput input) {
        state = parseState(input.getStringOr(PREFIX + "State", State.SELECT_INPUT.name()));
        savedDimension = input.getStringOr(PREFIX + "Dimension", "");
        furnacePosition = input.getBooleanOr(PREFIX + "HasPosition", false)
                ? BlockPos.of(input.getLongOr(PREFIX + "Position", 0L))
                : null;
        leaseId = parseUuid(input.getStringOr(PREFIX + "LeaseId", ""));
        plannedInput = input.read(PREFIX + "Input", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        plannedFuel = input.read(PREFIX + "Fuel", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        plannedResult = input.read(PREFIX + "Result", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        resultPerInput = Math.max(0, input.getIntOr(PREFIX + "ResultPerInput", 0));
        cookingTimePerInput = Math.max(0, input.getIntOr(PREFIX + "CookingTimePerInput", 0));
        waitTicks = Math.max(0, input.getIntOr(PREFIX + "WaitTicks", 0));
        cooldownTicks = Math.max(0, input.getIntOr(PREFIX + "CooldownTicks", 0));
        searchIterator = null;
        pathFailures = 0;
        stateTicks = 0;
        if (state != State.SELECT_INPUT && state != State.COOLDOWN
                && (plannedInput.isEmpty() || plannedFuel.isEmpty() || plannedResult.isEmpty())) {
            resetCycle(State.SELECT_INPUT);
        }
    }

    private static ItemStack removeExact(SimpleContainer storage, ItemStack requested) {
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack candidate = storage.getItem(slot);
            if (ItemStack.isSameItemSameComponents(candidate, requested)
                    && candidate.getCount() >= requested.getCount()) {
                return storage.removeItem(slot, requested.getCount());
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean containsAtLeast(SimpleContainer storage, ItemStack requested) {
        int count = 0;
        for (ItemStack stack : storage.getItems()) {
            if (ItemStack.isSameItemSameComponents(stack, requested)) {
                count += stack.getCount();
            }
        }
        return count >= requested.getCount();
    }

    private static boolean containsAtLeastAfterReservation(
            SimpleContainer storage,
            ItemStack requested,
            ItemStack reserved
    ) {
        int count = 0;
        for (ItemStack stack : storage.getItems()) {
            if (ItemStack.isSameItemSameComponents(stack, requested)) {
                count += stack.getCount();
            }
        }
        if (ItemStack.isSameItemSameComponents(requested, reserved)) {
            count -= reserved.getCount();
        }
        return count >= requested.getCount();
    }

    private static boolean isLoaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())
        ) != null;
    }

    private static State parseState(String value) {
        try {
            return State.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return State.SELECT_INPUT;
        }
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record SmeltPlan(
            ItemStack input,
            ItemStack fuel,
            ItemStack result,
            int resultPerInput,
            int cookingTimePerInput
    ) {
    }

    /** 熔炉流程的持久化阶段。 */
    private enum State {
        SELECT_INPUT,
        SEARCH_FURNACE,
        NAVIGATE,
        ACQUIRE_LEASE,
        INSERT,
        WAIT,
        COLLECT,
        COOLDOWN
    }
}
