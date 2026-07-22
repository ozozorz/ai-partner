package io.github.ozozorz.aipartner.work.rule;

import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import io.github.ozozorz.aipartner.work.MaidWorkContext;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.work.MaidWorkRule;
import io.github.ozozorz.aipartner.work.WorkActionResult;
import io.github.ozozorz.aipartner.work.WorkTarget;
import io.github.ozozorz.aipartner.work.supply.WorkSupplyRequirement;
import io.github.ozozorz.aipartner.work.complex.MiningSafety;
import io.github.ozozorz.aipartner.work.complex.FurnaceWorkRule;
import io.github.ozozorz.aipartner.work.complex.FishingWorkRule;
import io.github.ozozorz.aipartner.work.complex.NaturalTreeAnalyzer;
import io.github.ozozorz.aipartner.work.complex.NaturalTreePlan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 构造自然树砍伐、暴露矿石、原版熔炉和真实钓鱼规则。
 */
public final class ComplexWorkRules {
    private ComplexWorkRules() {
    }

    public static Collection<MaidWorkRule> create() {
        return List.of(new LumberjackRule(), new MinerRule(), new FurnaceWorkRule(), new FishingWorkRule());
    }

    /** 已批准整棵树后从树冠向根部逐段处理，避免重扫时把玩家结构误判为余树。 */
    private static final class LumberjackRule implements MaidWorkRule {
        private static final String PLAN_COUNT = "ComplexTreePlanCount";
        private static final String PLAN_POSITION_PREFIX = "ComplexTreePlanPosition";
        private static final WorkSupplyRequirement AXE_SUPPLY = new WorkSupplyRequirement(
                "lumberjack_axe",
                partner -> isAxe(partner.getMainHandItem())
                        || partner.getInventory().getItems().stream().anyMatch(LumberjackRule::isAxe),
                List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE),
                true
        );
        private final LinkedHashSet<BlockPos> approvedLogs = new LinkedHashSet<>();
        private boolean lastActionBareHand;

        @Override
        public MaidWorkMode mode() {
            return MaidWorkMode.LUMBERJACK;
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            pruneInvalid(context);
            if (!approvedLogs.isEmpty()) {
                return approvedLogs.contains(position) && state.is(BlockTags.OVERWORLD_NATURAL_LOGS);
            }
            NaturalTreePlan plan = NaturalTreeAnalyzer.analyze(context.level(), context.boundary(), position)
                    .orElse(null);
            if (plan == null) {
                return false;
            }
            approvedLogs.addAll(plan.orderedLogs());
            return false;
        }

        @Override
        public Optional<WorkTarget> findPriorityTarget(MaidWorkContext context) {
            pruneInvalid(context);
            // 导航始终锚定最后才处理的根部；真正动作仍按集合中的树冠到根部顺序执行。
            return approvedLogs.isEmpty()
                    ? Optional.empty()
                    : Optional.of(WorkTarget.block(approvedLogs.getLast()));
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            BlockPos position = target.fallbackPosition();
            return !target.isEntity()
                    && !approvedLogs.isEmpty()
                    && position.equals(approvedLogs.getLast())
                    && context.level().getBlockState(position).is(BlockTags.OVERWORLD_NATURAL_LOGS);
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            return Optional.of(AXE_SUPPLY);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            if (!context.actions().inventory().hasAnySpace()) {
                return WorkActionResult.BLOCKED;
            }
            EquipmentLease axeLease = EquipmentLease.acquire(context.partner(), LumberjackRule::isAxe).orElse(null);
            lastActionBareHand = axeLease == null;
            EquipmentLease lease = axeLease != null
                    ? axeLease
                    : EquipmentLease.acquireBareHand(context.partner()).orElse(null);
            if (lease == null) {
                return WorkActionResult.BLOCKED;
            }
            try (lease) {
                if (!isStillValid(context, target)) {
                    return WorkActionResult.RETRY;
                }
                BlockPos actionLog = approvedLogs.getFirst();
                if (!context.level().getBlockState(actionLog).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
                    approvedLogs.remove(actionLog);
                    return WorkActionResult.RETRY;
                }
                if (!context.actions().breakBlock().destroyWithDrops(context.level(), actionLog)) {
                    return WorkActionResult.RETRY;
                }
                approvedLogs.remove(actionLog);
                return WorkActionResult.SUCCESS;
            }
        }

        @Override
        public int successCooldownTicks() {
            return lastActionBareHand ? 60 : 20;
        }

        @Override
        public void onDeselected(ServerLevel level, io.github.ozozorz.aipartner.entity.AiPartnerEntity partner) {
            approvedLogs.clear();
        }

        @Override
        public void save(ValueOutput output) {
            output.putInt(PLAN_COUNT, approvedLogs.size());
            int index = 0;
            for (BlockPos position : approvedLogs) {
                output.putLong(PLAN_POSITION_PREFIX + index++, position.asLong());
            }
        }

        @Override
        public void load(ValueInput input) {
            approvedLogs.clear();
            int count = Math.clamp(input.getIntOr(PLAN_COUNT, 0), 0, 64);
            for (int index = 0; index < count; index++) {
                approvedLogs.add(BlockPos.of(input.getLongOr(PLAN_POSITION_PREFIX + index, 0L)));
            }
        }

        private void pruneInvalid(MaidWorkContext context) {
            approvedLogs.removeIf(position -> !context.isLegal(position)
                    || !context.level().getBlockState(position).is(BlockTags.OVERWORLD_NATURAL_LOGS));
        }

        private static boolean isAxe(ItemStack stack) {
            return !stack.isEmpty() && stack.typeHolder().is(ItemTags.AXES);
        }
    }

    /** 只处理安全暴露的原版矿石，并用真实镐等级、附魔、掉落和耐久完成破坏。 */
    private static final class MinerRule implements MaidWorkRule {
        @Override
        public MaidWorkMode mode() {
            return MaidWorkMode.MINER;
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return MiningSafety.isSafeExposedTarget(context, position)
                    && context.actions().inventory().hasAnySpace();
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            if (target.isEntity()) {
                return false;
            }
            BlockPos position = target.fallbackPosition();
            BlockState state = context.level().getBlockState(position);
            return matchesBlock(context, position, state);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            BlockState state = context.level().getBlockState(target.fallbackPosition());
            EquipmentLease lease = EquipmentLease.acquire(
                    context.partner(),
                    stack -> isSuitablePickaxe(stack, state)
            ).orElse(null);
            if (lease == null) {
                return WorkActionResult.BLOCKED;
            }
            try (lease) {
                return isStillValid(context, target)
                        && context.actions().breakBlock().destroyWithDrops(
                                context.level(),
                                target.fallbackPosition()
                        ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
            }
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            if (target == null || target.isEntity()) {
                return Optional.empty();
            }
            BlockState state = context.level().getBlockState(target.fallbackPosition());
            List<Item> candidates = List.of(
                            Items.WOODEN_PICKAXE,
                            Items.STONE_PICKAXE,
                            Items.IRON_PICKAXE,
                            Items.DIAMOND_PICKAXE
                    ).stream()
                    .filter(item -> isSuitablePickaxe(new ItemStack(item), state))
                    .toList();
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new WorkSupplyRequirement(
                    "miner_pickaxe_" + state.getBlock().getDescriptionId(),
                    partner -> isSuitablePickaxe(partner.getMainHandItem(), state)
                            || partner.getInventory().getItems().stream()
                            .anyMatch(stack -> isSuitablePickaxe(stack, state)),
                    candidates,
                    false
            ));
        }

        private static boolean isSuitablePickaxe(ItemStack stack, BlockState state) {
            return !stack.isEmpty()
                    && stack.typeHolder().is(ItemTags.PICKAXES)
                    && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state));
        }
    }
}
