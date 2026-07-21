package io.github.ozozorz.aipartner.work.complex;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.MaidFishingHookEntity;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import io.github.ozozorz.aipartner.registry.ModEntities;
import io.github.ozozorz.aipartner.work.MaidWorkContext;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.work.MaidWorkRule;
import io.github.ozozorz.aipartner.work.WorkActionResult;
import io.github.ozozorz.aipartner.work.WorkTarget;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 驱动女仆专属真实浮标的找水、抛竿、等待咬钩、收竿和拾取状态机。
 */
public final class FishingWorkRule implements MaidWorkRule {
    private static final int SEARCH_BUDGET_PER_TICK = 384;
    private static final int MAX_VERTICAL_SEARCH_RADIUS = 6;
    private static final int MIN_CAST_DISTANCE_FROM_BANK = 3;
    private static final int MAX_CAST_DISTANCE_FROM_BANK = 6;
    private static final int MAX_WAIT_TICKS = 1400;
    private static final int PICKUP_TIMEOUT_TICKS = 120;
    private static final double ARRIVAL_DISTANCE_SQUARED = 4.0;
    private static final double DROP_SEARCH_RADIUS = 8.0;
    private static final String COOLDOWN_TAG = "ComplexFishingCooldown";

    private State state = State.SEARCH_WATER;
    private @Nullable Iterator<BlockPos> scanIterator;
    private @Nullable BlockPos waterPosition;
    private @Nullable BlockPos bankPosition;
    private @Nullable UUID hookId;
    private @Nullable EquipmentLease rodLease;
    private int stateTicks;
    private int pathFailures;
    private int cooldownTicks;

    @Override
    public MaidWorkMode mode() {
        return MaidWorkMode.FISHER;
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

    /** 抛竿与收竿沿用原版钓鱼语义，不会修改世界方块。 */
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
        stateTicks++;
        switch (state) {
            case SEARCH_WATER -> searchWater(context);
            case NAVIGATE -> navigate(context);
            case EQUIP_ROD -> equipRod(context);
            case CAST -> cast(context);
            case WAIT_BITE -> waitForBite(context);
            case RETRIEVE -> retrieve(context);
            case PICK_UP -> pickUpLoot(context);
            case COOLDOWN -> tickCooldown();
        }
    }

    private void searchWater(MaidWorkContext context) {
        if (!hasFishingRod(context.partner())) {
            enterCooldown(context, 80);
            return;
        }
        if (scanIterator == null) {
            scanIterator = BlockPos.withinManhattan(
                    context.boundary().position(),
                    context.boundary().radius(),
                    Math.min(MAX_VERTICAL_SEARCH_RADIUS, context.boundary().radius()),
                    context.boundary().radius()
            ).iterator();
        }
        int checked = 0;
        while (scanIterator.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
            BlockPos candidate = scanIterator.next();
            if (!context.isLegal(candidate) || !isLoaded(context.level(), candidate)) {
                continue;
            }
            BlockPos bank = findSafeBank(context, candidate);
            if (bank == null) {
                continue;
            }
            waterPosition = candidate.immutable();
            bankPosition = bank;
            scanIterator = null;
            transition(State.NAVIGATE);
            return;
        }
        if (scanIterator != null && !scanIterator.hasNext()) {
            scanIterator = null;
            enterCooldown(context, 80);
        }
    }

    private void navigate(MaidWorkContext context) {
        if (!siteStillValid(context)) {
            resetSite(State.SEARCH_WATER);
            return;
        }
        if (context.partner().distanceToSqr(bankPosition.getCenter()) <= ARRIVAL_DISTANCE_SQUARED) {
            context.actions().navigation().stop();
            transition(State.EQUIP_ROD);
            return;
        }
        if (stateTicks % 10 != 1) {
            return;
        }
        boolean started = context.actions().navigation().moveTo(bankPosition, 0.9);
        pathFailures = started ? 0 : pathFailures + 1;
        if (pathFailures > context.partner().getWorkPathRetryLimit(3)) {
            resetSite(State.SEARCH_WATER);
        }
    }

    private void equipRod(MaidWorkContext context) {
        rodLease = EquipmentLease.acquire(
                context.partner(),
                stack -> !stack.isEmpty() && stack.is(Items.FISHING_ROD)
        ).orElse(null);
        if (rodLease == null) {
            resetSite(State.SEARCH_WATER);
            return;
        }
        transition(State.CAST);
    }

    private void cast(MaidWorkContext context) {
        if (!siteStillValid(context) || rodLease == null || !rodLease.isUsable()) {
            suspendAndReset(context.level());
            return;
        }
        MaidFishingHookEntity hook = new MaidFishingHookEntity(ModEntities.MAID_FISHING_HOOK, context.level());
        hook.initializeCast(
                context.partner(),
                context.level(),
                context.partner().getMainHandItem(),
                waterPosition
        );
        if (!context.level().addFreshEntity(hook)) {
            enterCooldown(context, 60);
            return;
        }
        hookId = hook.getUUID();
        context.partner().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        context.level().playSound(
                null,
                context.partner().getX(),
                context.partner().getY(),
                context.partner().getZ(),
                SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL,
                0.5F,
                1.0F
        );
        transition(State.WAIT_BITE);
    }

    private void waitForBite(MaidWorkContext context) {
        MaidFishingHookEntity hook = resolveHook(context.level());
        if (hook == null || hook.failedCast()) {
            hookId = null;
            transition(State.CAST);
            return;
        }
        if (hook.isBiting()) {
            transition(State.RETRIEVE);
            return;
        }
        if (stateTicks > MAX_WAIT_TICKS) {
            hook.discard();
            hookId = null;
            transition(State.CAST);
        }
    }

    private void retrieve(MaidWorkContext context) {
        MaidFishingHookEntity hook = resolveHook(context.level());
        if (hook == null || rodLease == null || !rodLease.isUsable()) {
            suspendAndReset(context.level());
            return;
        }
        int lootStacks = hook.retrieve(context.partner().getMainHandItem());
        hookId = null;
        closeRodLease();
        if (lootStacks <= 0) {
            enterCooldown(context, 40);
            return;
        }
        context.partner().rewardWorkCompletion(MaidWorkMode.FISHER);
        transition(State.PICK_UP);
    }

    private void pickUpLoot(MaidWorkContext context) {
        ItemEntity drop = context.actions().pickupItem().findNearest(
                context.level(),
                context.partner().blockPosition(),
                DROP_SEARCH_RADIUS,
                entity -> context.isLegal(entity.blockPosition())
                        && context.partner().wantsToPickUp(context.level(), entity.getItem())
        ).orElse(null);
        if (drop == null || stateTicks >= PICKUP_TIMEOUT_TICKS) {
            enterCooldown(context, 30);
            return;
        }
        if (stateTicks % 5 == 1) {
            context.actions().pickupItem().moveTo(drop, 1.0);
        }
    }

    private void tickCooldown() {
        if (--cooldownTicks <= 0) {
            resetSite(State.SEARCH_WATER);
        }
    }

    @Override
    public void onSuspended(MaidWorkContext context) {
        suspendAndReset(context.level());
    }

    @Override
    public void onDeselected(ServerLevel level, AiPartnerEntity partner) {
        suspendAndReset(level);
    }

    private void suspendAndReset(ServerLevel level) {
        MaidFishingHookEntity hook = resolveHook(level);
        if (hook != null) {
            hook.discard();
        }
        hookId = null;
        closeRodLease();
        resetSite(State.SEARCH_WATER);
    }

    private void closeRodLease() {
        if (rodLease != null) {
            rodLease.close();
            rodLease = null;
        }
    }

    private void enterCooldown(MaidWorkContext context, int ticks) {
        cooldownTicks = context.partner().adjustWorkCooldown(ticks);
        transition(State.COOLDOWN);
    }

    private void resetSite(State next) {
        scanIterator = null;
        waterPosition = null;
        bankPosition = null;
        pathFailures = 0;
        transition(next);
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private boolean siteStillValid(MaidWorkContext context) {
        return waterPosition != null
                && bankPosition != null
                && context.isLegal(waterPosition)
                && context.isLegal(bankPosition)
                && isSafeCastSite(context, waterPosition, bankPosition);
    }

    private static @Nullable BlockPos findSafeBank(MaidWorkContext context, BlockPos water) {
        if (!hasOpenWaterArea(context, water)) {
            return null;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance = MIN_CAST_DISTANCE_FROM_BANK; distance <= MAX_CAST_DISTANCE_FROM_BANK; distance++) {
                BlockPos bank = FishingSiteGeometry.bankStandPosition(water, direction, distance);
                if (context.isLegal(bank) && isSafeCastSite(context, water, bank)) {
                    return bank.immutable();
                }
            }
        }
        return null;
    }

    /** 原版开放水域要求浮标周围 5×5、两层深的区域为水，上方两层无遮挡。 */
    private static boolean hasOpenWaterArea(MaidWorkContext context, BlockPos center) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos surface = center.offset(x, 0, z);
                BlockPos below = surface.below();
                if (!isSourceWater(context, surface)
                        || !isSourceWater(context, below)
                        || !isClearAboveWater(context, surface.above())
                        || !isClearAboveWater(context, surface.above(2))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSafeCastSite(
            MaidWorkContext context,
            BlockPos water,
            BlockPos bank
    ) {
        if (!hasOpenWaterArea(context, water) || !isStandable(context, bank)) {
            return false;
        }
        if (!FishingSiteGeometry.isAligned(
                water,
                bank,
                MIN_CAST_DISTANCE_FROM_BANK,
                MAX_CAST_DISTANCE_FROM_BANK
        )) {
            return false;
        }
        int distance = FishingSiteGeometry.horizontalDistance(water, bank);
        for (int step = 1; step < distance; step++) {
            BlockPos lane = FishingSiteGeometry.lanePosition(water, bank, step);
            if (!isSourceWater(context, lane) || !isClearAboveWater(context, lane.above())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSourceWater(MaidWorkContext context, BlockPos position) {
        return context.level().getFluidState(position).is(FluidTags.WATER)
                && context.level().getFluidState(position).isSource()
                && context.level().getBlockState(position).getCollisionShape(context.level(), position).isEmpty();
    }

    private static boolean isClearAboveWater(MaidWorkContext context, BlockPos position) {
        return context.level().getBlockState(position).getCollisionShape(context.level(), position).isEmpty();
    }

    private static boolean isStandable(MaidWorkContext context, BlockPos position) {
        BlockState feet = context.level().getBlockState(position);
        BlockState head = context.level().getBlockState(position.above());
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && feet.getCollisionShape(context.level(), position).isEmpty()
                && head.getCollisionShape(context.level(), position.above()).isEmpty()
                && !feet.is(Blocks.FIRE)
                && !feet.is(Blocks.SOUL_FIRE)
                && context.level().getBlockState(position.below()).isFaceSturdy(
                        context.level(),
                        position.below(),
                        Direction.UP
                );
    }

    private MaidFishingHookEntity resolveHook(ServerLevel level) {
        if (hookId == null) {
            return null;
        }
        Entity entity = level.getEntity(hookId);
        return entity instanceof MaidFishingHookEntity hook ? hook : null;
    }

    private static boolean hasFishingRod(AiPartnerEntity partner) {
        return partner.getMainHandItem().is(Items.FISHING_ROD)
                || partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(Items.FISHING_ROD));
    }

    private static boolean isLoaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())
        ) != null;
    }

    @Override
    public void save(ValueOutput output) {
        // 活动浮标不进入区块存档；重启后只保留一个短冷却并重新寻找安全水边。
        output.putInt(COOLDOWN_TAG, state == State.COOLDOWN ? Math.max(0, cooldownTicks) : 0);
    }

    @Override
    public void load(ValueInput input) {
        hookId = null;
        rodLease = null;
        scanIterator = null;
        waterPosition = null;
        bankPosition = null;
        pathFailures = 0;
        cooldownTicks = Math.max(0, input.getIntOr(COOLDOWN_TAG, 0));
        transition(cooldownTicks > 0 ? State.COOLDOWN : State.SEARCH_WATER);
    }

    /** 钓鱼流程的服务端权威阶段。 */
    private enum State {
        SEARCH_WATER,
        NAVIGATE,
        EQUIP_ROD,
        CAST,
        WAIT_BITE,
        RETRIEVE,
        PICK_UP,
        COOLDOWN
    }
}
