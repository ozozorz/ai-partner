package io.github.ozozorz.aipartner.work;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.life.ActivityLocation;
import io.github.ozozorz.aipartner.life.MaidLifeController;
import io.github.ozozorz.aipartner.skill.MaidSkillSet;
import io.github.ozozorz.aipartner.work.supply.MaidWorkSupplyController;
import io.github.ozozorz.aipartner.work.supply.WorkSupplyRequirement;
import java.util.Iterator;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 在 WORK 日程内以有界扫描推进“搜索—导航—复验—动作—冷却”通用状态机。
 */
public final class MaidWorkController {
    private static final int SEARCH_BUDGET_PER_TICK = 384;
    private static final int MAX_VERTICAL_SCAN_RADIUS = 6;
    private static final int EMPTY_SEARCH_COOLDOWN_TICKS = 40;
    private static final int BLOCKED_COOLDOWN_TICKS = 60;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int DROP_COLLECTION_TIMEOUT_TICKS = 100;
    private static final double DROP_COLLECTION_RADIUS = 4.0;
    private static final double INTERACTION_DISTANCE_SQUARED = 9.0;

    private final AiPartnerEntity partner;
    private final MaidLifeController lifeController;
    private final MaidSkillSet skills;
    private final MaidWorkRegistry registry;
    private final MaidWorkSupplyController supplyController;

    private MaidWorkMode mode = MaidWorkMode.NONE;
    private State state = State.IDLE;
    private @Nullable MaidWorkRule activeRule;
    private @Nullable WorkTarget target;
    private @Nullable BlockPos actionPosition;
    private @Nullable Iterator<BlockPos> scanIterator;
    private int cooldownTicks;
    private int pathFailures;
    private int stateTicks;

    public MaidWorkController(
            AiPartnerEntity partner,
            MaidLifeController lifeController,
            MaidSkillSet skills
    ) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.lifeController = Objects.requireNonNull(lifeController, "lifeController");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.registry = MaidWorkRegistry.createDefault();
        this.supplyController = new MaidWorkSupplyController(partner, skills);
    }

    public MaidWorkMode mode() {
        return mode;
    }

    /**
     * 切换工作技能组合会丢弃可重算的扫描目标，但不会触碰背包。
     */
    public void setMode(MaidWorkMode mode) {
        MaidWorkMode next = Objects.requireNonNull(mode, "mode");
        if (this.mode != next && partner.level() instanceof ServerLevel level) {
            registry.ruleFor(this.mode).ifPresent(rule -> rule.onDeselected(level, partner));
        }
        this.mode = next;
        supplyController.reset();
        skills.navigation().stop();
        resetState();
    }

    public String executionState() {
        if (supplyController.isPreparing()) {
            return mode.name() + "_PREPARING_" + supplyController.executionState();
        }
        MaidWorkRule selected = registry.ruleFor(mode).orElse(null);
        return selected != null && selected.managesOwnExecution()
                ? mode.name() + '_' + selected.managedExecutionState()
                : mode.name() + '_' + state.name();
    }

    /**
     * 工作控制器接管导航时，Brain 不再同时产生日程或跟随路径。
     */
    public boolean controlsMovement() {
        return mode != MaidWorkMode.NONE
                && lifeController.canPerformScheduledWork()
                && state != State.IDLE;
    }

    /**
     * 动作或回收阶段允许实体吸收附近的工作掉落物。
     */
    public boolean acceptsWorkDrops() {
        return controlsMovement() && (state == State.ACTING || state == State.PICKING_UP);
    }

    public void tick() {
        if (!(partner.level() instanceof ServerLevel level)) {
            return;
        }
        MaidWorkRule rule = registry.ruleFor(mode).orElse(null);
        ActivityLocation boundary = lifeController.scheduledWorkBoundary().orElse(null);
        if (mode == MaidWorkMode.NONE
                || rule == null
                || boundary == null
                || !lifeController.canPerformScheduledWork()
                || !boundary.contains(level, partner.blockPosition())) {
            suspendManagedRule(level, boundary);
            supplyController.reset();
            resetState();
            return;
        }
        if (rule.requiresMobGriefing() && !level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            suspendManagedRule(level, boundary);
            supplyController.reset();
            enterCooldown(BLOCKED_COOLDOWN_TICKS);
            return;
        }

        if (!skills.supports(mode.requiredSkills())) {
            enterCooldown(BLOCKED_COOLDOWN_TICKS);
            return;
        }
        MaidWorkContext context = new MaidWorkContext(partner, level, boundary, skills);
        if (activeRule != rule) {
            resetState();
            activeRule = rule;
            state = rule.managesOwnExecution() ? State.IDLE : State.SEARCHING;
        }
        if (rule.managesOwnExecution()) {
            if (prepareSupply(context, rule, null)) {
                rule.onSuspended(context);
                return;
            }
            rule.tickManaged(context);
            return;
        }
        stateTicks++;
        switch (state) {
            case IDLE -> {
                activeRule = rule;
                state = State.SEARCHING;
                stateTicks = 0;
            }
            case SEARCHING -> search(context, rule);
            case NAVIGATING -> navigate(context, rule);
            case ACTING -> act(context, rule);
            case PICKING_UP -> pickUpDrops(context, rule);
            case COOLDOWN -> tickCooldown();
        }
    }

    public void save(ValueOutput output) {
        output.putString("SelectedWorkMode", mode.serializedName());
        registry.ruleFor(mode).ifPresent(rule -> rule.save(output));
    }

    public void load(ValueInput input) {
        mode = MaidWorkMode.fromSavedName(input.getStringOr("SelectedWorkMode", MaidWorkMode.NONE.serializedName()));
        registry.ruleFor(mode).ifPresent(rule -> rule.load(input));
        supplyController.reset();
        resetState();
    }

    /**
     * 实体被销毁前释放工具、浮标和工作站租约等运行时资源。
     * 存档卸载不会调用此入口，持久化工作仍可在重新加载后复验并恢复。
     */
    public void shutdown() {
        if (partner.level() instanceof ServerLevel level) {
            registry.ruleFor(mode).ifPresent(rule -> rule.onDeselected(level, partner));
        }
        supplyController.reset();
        skills.navigation().stop();
        resetState();
    }

    private void search(MaidWorkContext context, MaidWorkRule rule) {
        WorkTarget priorityTarget = rule.findPriorityTarget(context)
                .filter(found -> context.isLegal(found.currentPosition(context.level())))
                .orElse(null);
        if (priorityTarget != null) {
            target = priorityTarget;
            scanIterator = null;
            transition(State.NAVIGATING);
            return;
        }
        if (rule.prioritizesEntityTargets()) {
            WorkTarget entityTarget = rule.findEntityTarget(context)
                    .filter(found -> context.isLegal(found.currentPosition(context.level())))
                    .orElse(null);
            if (entityTarget != null) {
                target = entityTarget;
                scanIterator = null;
                transition(State.NAVIGATING);
                return;
            }
        }
        if (rule.scansBlocks()) {
            if (scanIterator == null) {
                ActivityLocation boundary = context.boundary();
                scanIterator = BlockPos.withinManhattan(
                        boundary.position(),
                        boundary.radius(),
                        Math.min(MAX_VERTICAL_SCAN_RADIUS, boundary.radius()),
                        boundary.radius()
                ).iterator();
            }
            int checked = 0;
            while (scanIterator.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
                BlockPos candidate = scanIterator.next();
                if (!context.isLegal(candidate)
                        || context.level().getChunkSource().getChunkNow(
                                SectionPos.blockToSectionCoord(candidate.getX()),
                                SectionPos.blockToSectionCoord(candidate.getZ())
                        ) == null) {
                    continue;
                }
                BlockState stateAtTarget = context.level().getBlockState(candidate);
                if (rule.matchesBlock(context, candidate, stateAtTarget)) {
                    target = WorkTarget.block(candidate.immutable());
                    scanIterator = null;
                    transition(State.NAVIGATING);
                    return;
                }
                priorityTarget = rule.findPriorityTarget(context)
                        .filter(found -> context.isLegal(found.currentPosition(context.level())))
                        .orElse(null);
                if (priorityTarget != null) {
                    target = priorityTarget;
                    scanIterator = null;
                    transition(State.NAVIGATING);
                    return;
                }
            }
            if (scanIterator != null && scanIterator.hasNext()) {
                return;
            }
            scanIterator = null;
        }

        target = rule.findEntityTarget(context).filter(found -> context.isLegal(
                found.currentPosition(context.level())
        )).orElse(null);
        if (target == null) {
            enterCooldown(EMPTY_SEARCH_COOLDOWN_TICKS);
        } else {
            transition(State.NAVIGATING);
        }
    }

    private void navigate(MaidWorkContext context, MaidWorkRule rule) {
        if (target == null || !isTargetLegalAndValid(context, rule)) {
            supplyController.reset();
            retrySearch();
            return;
        }
        if (prepareSupply(context, rule, target)) {
            return;
        }
        BlockPos position = target.currentPosition(context.level());
        if (partner.distanceToSqr(position.getCenter()) <= INTERACTION_DISTANCE_SQUARED) {
            skills.navigation().stop();
            transition(State.ACTING);
            return;
        }
        if (stateTicks % 10 != 1) {
            return;
        }
        boolean pathStarted = target.resolveEntity(context.level())
                .map(entity -> partner.getNavigation().moveTo(entity, 0.9))
                .orElseGet(() -> skills.navigation().moveTo(position, 0.9));
        pathFailures = pathStarted ? 0 : pathFailures + 1;
        if (pathFailures > partner.getWorkPathRetryLimit(MAX_PATH_FAILURES)) {
            enterCooldown(BLOCKED_COOLDOWN_TICKS);
        }
    }

    private void act(MaidWorkContext context, MaidWorkRule rule) {
        if (target == null || !isTargetLegalAndValid(context, rule)) {
            supplyController.reset();
            retrySearch();
            return;
        }
        if (prepareSupply(context, rule, target)) {
            return;
        }
        if (partner.distanceToSqr(target.currentPosition(context.level()).getCenter())
                > INTERACTION_DISTANCE_SQUARED) {
            transition(State.NAVIGATING);
            return;
        }
        WorkActionResult result = rule.perform(context, target);
        switch (result) {
            case SUCCESS -> {
                partner.rewardWorkCompletion(mode);
                supplyController.retryWithNewMaterials();
                beginDropCollection(context, rule);
            }
            case RETRY -> retrySearch();
            case BLOCKED -> enterCooldown(BLOCKED_COOLDOWN_TICKS);
        }
    }

    /**
     * 成功动作先进入短暂的掉落物回收阶段；若动作没有生成实体物品则直接冷却。
     */
    private void beginDropCollection(MaidWorkContext context, MaidWorkRule rule) {
        actionPosition = target == null ? partner.blockPosition() : target.currentPosition(context.level()).immutable();
        target = null;
        scanIterator = null;
        pathFailures = 0;
        if (nearestCollectibleDrop(context).isPresent()) {
            transition(State.PICKING_UP);
        } else {
            actionPosition = null;
            enterCooldown(rule.successCooldownTicks());
        }
    }

    /** 追踪动作点附近的原版掉落实体，直到全部拾取或达到有界超时。 */
    private void pickUpDrops(MaidWorkContext context, MaidWorkRule rule) {
        ItemEntity drop = nearestCollectibleDrop(context).orElse(null);
        if (drop == null || stateTicks >= DROP_COLLECTION_TIMEOUT_TICKS) {
            skills.navigation().stop();
            actionPosition = null;
            enterCooldown(rule.successCooldownTicks());
            return;
        }
        if (stateTicks % 5 == 1) {
            skills.pickupItem().moveTo(drop, 1.0);
        }
    }

    private java.util.Optional<ItemEntity> nearestCollectibleDrop(MaidWorkContext context) {
        if (actionPosition == null) {
            return java.util.Optional.empty();
        }
        return skills.pickupItem().findNearest(
                context.level(),
                actionPosition,
                DROP_COLLECTION_RADIUS,
                entity -> context.isLegal(entity.blockPosition())
                        && partner.wantsToPickUp(context.level(), entity.getItem())
        );
    }

    private boolean isTargetLegalAndValid(MaidWorkContext context, MaidWorkRule rule) {
        BlockPos currentPosition = target.currentPosition(context.level());
        return context.isLegal(currentPosition) && rule.isStillValid(context, target);
    }

    /**
     * 把具体规则声明的物资需求交给独立准备状态机；允许降级的规则在无法制作时继续执行。
     */
    private boolean prepareSupply(
            MaidWorkContext context,
            MaidWorkRule rule,
            @Nullable WorkTarget currentTarget
    ) {
        WorkSupplyRequirement requirement = rule.supplyRequirement(context, currentTarget).orElse(null);
        if (requirement == null) {
            supplyController.reset();
            return false;
        }
        MaidWorkSupplyController.PreparationStatus status = supplyController.tick(context, requirement);
        return status == MaidWorkSupplyController.PreparationStatus.PREPARING
                || (status == MaidWorkSupplyController.PreparationStatus.UNAVAILABLE
                && !requirement.allowsFallback());
    }

    private void tickCooldown() {
        if (--cooldownTicks <= 0) {
            retrySearch();
        }
    }

    private void retrySearch() {
        target = null;
        actionPosition = null;
        scanIterator = null;
        pathFailures = 0;
        transition(State.SEARCHING);
    }

    private void enterCooldown(int ticks) {
        target = null;
        actionPosition = null;
        scanIterator = null;
        pathFailures = 0;
        cooldownTicks = partner.adjustWorkCooldown(Math.max(1, ticks));
        transition(State.COOLDOWN);
    }

    private void suspendManagedRule(ServerLevel level, @Nullable ActivityLocation boundary) {
        if (activeRule == null || !activeRule.managesOwnExecution() || boundary == null) {
            return;
        }
        activeRule.onSuspended(new MaidWorkContext(partner, level, boundary, skills));
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private void resetState() {
        state = State.IDLE;
        activeRule = null;
        target = null;
        actionPosition = null;
        scanIterator = null;
        cooldownTicks = 0;
        pathFailures = 0;
        stateTicks = 0;
    }

    private enum State {
        IDLE,
        SEARCHING,
        NAVIGATING,
        ACTING,
        PICKING_UP,
        COOLDOWN
    }
}
