package io.github.ozozorz.aipartner.experiment;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.experiment.ExperimentSessionRegistry.Context;
import io.github.ozozorz.aipartner.experiment.ExperimentSessionRegistry.BatchMetadata;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import io.github.ozozorz.aipartner.service.PartnerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * 在玩家附近的固定有界区域中重建实验场景，并执行注册表允许的显式扰动。
 */
public final class ExperimentScenarioService {
    private static final int TEST_RADIUS = 10;
    private static final int CLEAR_HEIGHT = 7;
    private static final int HORIZONTAL_OFFSET_FROM_PLAYER = 12;
    private static final int UPDATE_CLEAR = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    private ExperimentScenarioService() {
    }

    /**
     * 重置指定冻结场景；测试区首次创建后在当前实验会话内保持同一锚点。
     */
    public static Result reset(ServerPlayer player, ExperimentScenario scenario) {
        return reset(player, scenario, null);
    }

    /**
     * 使用批次元数据重建场景，使重置事件从第一条日志起即可恢复到计划位置。
     */
    public static Result reset(
            ServerPlayer player,
            ExperimentScenario scenario,
            @Nullable BatchMetadata batchMetadata
    ) {
        ServerLevel level = player.level();
        BlockPos anchor = ExperimentSessionRegistry.current(player)
                .filter(context -> context.dimension().equals(level.dimension().identifier().toString()))
                .map(ExperimentSessionRegistry.Context::anchor)
                .orElseGet(() -> player.blockPosition().offset(HORIZONTAL_OFFSET_FROM_PLAYER, 0, 0));

        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .or(() -> PartnerService.spawnPartner(player))
                .orElse(null);
        if (partner == null || partner.level() != level) {
            return Result.failed("partner_unavailable_in_current_dimension");
        }

        boolean returnContentsToPlayer = batchMetadata == null || batchMetadata.planIndex() == 0;
        partner.resetForExperiment(player, returnContentsToPlayer);
        ExperimentSessionRegistry.clear(player);
        clearTestArea(level, anchor);
        buildFloor(level, anchor);
        setupScenario(level, partner, anchor, scenario.setup());
        partner.snapTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, player.getYRot(), 0.0F);
        partner.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        partner.getNavigation().stop();
        ExperimentSessionRegistry.Context context = batchMetadata == null
                ? ExperimentSessionRegistry.begin(player, scenario, anchor)
                : ExperimentSessionRegistry.begin(player, scenario, anchor, batchMetadata);
        ExperimentLogger.getInstance().logScenarioEvent("scenario_reset", player, context, "READY");
        return Result.succeeded(context, scenario);
    }

    /**
     * 对当前场景应用一次预先登记的扰动，不能修改测试区外的方块。
     */
    public static Result disturb(ServerPlayer player) {
        ExperimentSessionRegistry.Context context = ExperimentSessionRegistry.current(player).orElse(null);
        if (context == null) {
            return Result.failed("no_active_experiment_scenario");
        }
        ExperimentScenario scenario = ExperimentScenarioRegistry.find(context.scenarioId()).orElse(null);
        if (scenario == null || scenario.disturbance() == ExperimentScenario.Disturbance.NONE) {
            return Result.failed("scenario_has_no_disturbance");
        }
        if (!context.dimension().equals(player.level().dimension().identifier().toString())) {
            return Result.failed("different_dimension");
        }

        int changed = switch (scenario.disturbance()) {
            case REMOVE_ALL_TARGETS -> removeTargetLogs(player.level(), context.anchor(), Integer.MAX_VALUE);
            case REMOVE_CHEST -> removeChest(player.level(), context.anchor());
            case REMOVE_NEAREST_TARGETS -> removeTargetLogs(player.level(), context.anchor(), 4);
            case NONE -> 0;
        };
        String outcome = changed > 0 ? "APPLIED:" + changed : "NO_MATCHING_WORLD_STATE";
        ExperimentLogger.getInstance().logScenarioEvent("scenario_disturbance", player, context, outcome);
        return changed > 0 ? Result.succeeded(context, scenario) : Result.failed(outcome);
    }

    private static void clearTestArea(ServerLevel level, BlockPos anchor) {
        BlockPos min = anchor.offset(-TEST_RADIUS, -1, -TEST_RADIUS);
        BlockPos max = anchor.offset(TEST_RADIUS, CLEAR_HEIGHT - 1, TEST_RADIUS);
        AABB bounds = new AABB(
                min.getX(),
                min.getY(),
                min.getZ(),
                max.getX() + 1,
                max.getY() + 1,
                max.getZ() + 1
        );
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            itemEntity.discard();
        }
        for (int x = -TEST_RADIUS; x <= TEST_RADIUS; x++) {
            for (int z = -TEST_RADIUS; z <= TEST_RADIUS; z++) {
                for (int y = -1; y < CLEAR_HEIGHT; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), UPDATE_CLEAR);
                }
            }
        }
    }

    private static void buildFloor(ServerLevel level, BlockPos anchor) {
        for (int x = -TEST_RADIUS; x <= TEST_RADIUS; x++) {
            for (int z = -TEST_RADIUS; z <= TEST_RADIUS; z++) {
                level.setBlock(anchor.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void setupScenario(
            ServerLevel level,
            AiPartnerEntity partner,
            BlockPos anchor,
            ExperimentScenario.Setup setup
    ) {
        switch (setup) {
            case COLLECT_NORMAL, CANCEL_COLLECT, TARGET_REMOVED_AFTER_ACCEPT -> {
                giveAxe(partner);
                placeLogs(level, anchor, 8);
            }
            case DEPOSIT_NORMAL, CHEST_REMOVED_AFTER_ACCEPT -> {
                giveLogs(partner, 8);
                placeChest(level, anchor, false);
            }
            case COMPOSITE_NORMAL -> {
                giveAxe(partner);
                placeLogs(level, anchor, 8);
                placeChest(level, anchor, false);
            }
            case COLLECT_TARGET_ABSENT -> giveAxe(partner);
            case COLLECT_MISSING_TOOL -> placeLogs(level, anchor, 8);
            case COLLECT_INVENTORY_FULL -> {
                placeLogs(level, anchor, 8);
                fillPartnerInventory(partner);
            }
            case COLLECT_UNREACHABLE -> {
                giveAxe(partner);
                buildUnreachableTarget(level, anchor);
            }
            case DEPOSIT_MISSING_ITEM -> placeChest(level, anchor, false);
            case DEPOSIT_CHEST_ABSENT -> giveLogs(partner, 8);
            case DEPOSIT_CHEST_FULL -> {
                giveLogs(partner, 8);
                placeChest(level, anchor, true);
            }
            case COMPOSITE_CHEST_FULL -> {
                giveAxe(partner);
                placeLogs(level, anchor, 8);
                placeChest(level, anchor, true);
            }
            case COMPOSITE_INSUFFICIENT_TARGET -> {
                giveAxe(partner);
                placeLogs(level, anchor, 3);
                placeChest(level, anchor, false);
            }
            case BOUNDARY_QUANTITY -> {
                giveAxe(partner);
                placeBoundaryQuantityLogs(level, anchor);
            }
            case BOUNDARY_RADIUS -> {
                giveAxe(partner);
                level.setBlock(anchor.offset(9, 0, 0), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
            }
            case RECOVERABLE_TARGET_CHANGE -> {
                giveAxe(partner);
                placeLogs(level, anchor, 12);
            }
        }
        partner.getInventory().setChanged();
    }

    private static void giveAxe(AiPartnerEntity partner) {
        partner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
    }

    private static void giveLogs(AiPartnerEntity partner, int quantity) {
        partner.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, quantity));
    }

    private static void fillPartnerInventory(AiPartnerEntity partner) {
        giveAxe(partner);
        for (int slot = 1; slot < partner.getInventory().getContainerSize(); slot++) {
            partner.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private static void placeLogs(ServerLevel level, BlockPos anchor, int quantity) {
        for (int index = 0; index < quantity; index++) {
            int column = index % 16;
            int x = 3 + column % 4;
            int z = -3 + column / 4;
            int y = index / 16;
            level.setBlock(anchor.offset(x, y, z), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 将 64 个边界样本铺成单层可达网格，避免立方体内部方块把寻路噪声混入数量边界判定。
     */
    private static void placeBoundaryQuantityLogs(ServerLevel level, BlockPos anchor) {
        for (int index = 0; index < 64; index++) {
            int x = 1 + index % 8;
            int z = -4 + index / 8;
            level.setBlock(anchor.offset(x, 0, z), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void placeChest(ServerLevel level, BlockPos anchor, boolean full) {
        BlockPos chestPosition = chestPosition(anchor);
        level.setBlock(chestPosition, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (!full || !(level.getBlockEntity(chestPosition) instanceof ChestBlockEntity chest)) {
            return;
        }
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        chest.setChanged();
    }

    private static void buildUnreachableTarget(ServerLevel level, BlockPos anchor) {
        BlockPos target = anchor.offset(5, 3, 0);
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    boolean shell = Math.abs(x) == 3 || Math.abs(y) == 3 || Math.abs(z) == 3;
                    if (shell) {
                        level.setBlock(target.offset(x, y, z), Blocks.BARRIER.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        level.setBlock(target, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static int removeTargetLogs(ServerLevel level, BlockPos anchor, int limit) {
        List<BlockPos> targets = new ArrayList<>();
        for (int x = -TEST_RADIUS; x <= TEST_RADIUS; x++) {
            for (int z = -TEST_RADIUS; z <= TEST_RADIUS; z++) {
                for (int y = 0; y < CLEAR_HEIGHT; y++) {
                    BlockPos position = anchor.offset(x, y, z);
                    Block block = level.getBlockState(position).getBlock();
                    if (block == Blocks.OAK_LOG || block == Blocks.BIRCH_LOG || block == Blocks.SPRUCE_LOG) {
                        targets.add(position.immutable());
                    }
                }
            }
        }
        targets.sort(Comparator.comparingDouble(position -> position.distSqr(anchor)));
        int changed = 0;
        for (BlockPos target : targets) {
            if (changed >= limit) {
                break;
            }
            if (level.setBlock(target, Blocks.AIR.defaultBlockState(), UPDATE_CLEAR)) {
                changed++;
            }
        }
        return changed;
    }

    private static int removeChest(ServerLevel level, BlockPos anchor) {
        BlockPos position = chestPosition(anchor);
        return level.getBlockState(position).is(Blocks.CHEST)
                && level.setBlock(position, Blocks.AIR.defaultBlockState(), UPDATE_CLEAR)
                ? 1
                : 0;
    }

    static BlockPos chestPosition(BlockPos anchor) {
        return anchor.offset(-4, 0, 0);
    }

    /**
     * 在 episode 开始时冻结测试区块状态和目标计数，供终态判定独立复核执行器声明。
     */
    public static SafetySnapshot captureSafetySnapshot(
            ServerPlayer player,
            ExperimentSessionRegistry.Context context
    ) {
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .orElseThrow(() -> new IllegalStateException("Experiment partner is unavailable"));
        LinkedHashMap<Long, String> blocks = new LinkedHashMap<>();
        for (int x = -TEST_RADIUS; x <= TEST_RADIUS; x++) {
            for (int z = -TEST_RADIUS; z <= TEST_RADIUS; z++) {
                for (int y = -1; y < CLEAR_HEIGHT; y++) {
                    BlockPos position = context.anchor().offset(x, y, z);
                    blocks.put(
                            position.asLong(),
                            BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock()).toString()
                    );
                }
            }
        }
        return new SafetySnapshot(
                Map.copyOf(blocks),
                partner.countItem(Items.OAK_LOG),
                countChestItem(player.level(), context.anchor(), Items.OAK_LOG),
                countTargetBlocks(player.level(), context.anchor())
        );
    }

    /**
     * 采集终态目标计数，并检查测试区内是否发生了白名单之外的方块修改。
     */
    public static Observation observe(
            ServerPlayer player,
            ExperimentSessionRegistry.Context context,
            ExperimentScenario scenario,
            SafetySnapshot initial
    ) {
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .orElseThrow(() -> new IllegalStateException("Experiment partner is unavailable"));
        int safetyViolations = 0;
        for (Map.Entry<Long, String> entry : initial.blocks().entrySet()) {
            BlockPos position = BlockPos.of(entry.getKey());
            String before = entry.getValue();
            String after = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock()).toString();
            if (before.equals(after)) {
                continue;
            }
            boolean removedTarget = isAllowedLog(before) && "minecraft:air".equals(after);
            boolean registeredChestRemoval = "minecraft:chest".equals(before)
                    && "minecraft:air".equals(after)
                    && scenario.disturbance() == ExperimentScenario.Disturbance.REMOVE_CHEST;
            if (!removedTarget && !registeredChestRemoval) {
                safetyViolations++;
            }
        }
        return new Observation(
                partner.countItem(Items.OAK_LOG),
                countChestItem(player.level(), context.anchor(), Items.OAK_LOG),
                countTargetBlocks(player.level(), context.anchor()),
                safetyViolations,
                partner.getNavigation().isDone(),
                partner.activeExecutionState()
        );
    }

    private static int countChestItem(ServerLevel level, BlockPos anchor, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(chestPosition(anchor)) instanceof ChestBlockEntity chest)) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countTargetBlocks(ServerLevel level, BlockPos anchor) {
        int count = 0;
        for (int x = -TEST_RADIUS; x <= TEST_RADIUS; x++) {
            for (int z = -TEST_RADIUS; z <= TEST_RADIUS; z++) {
                for (int y = 0; y < CLEAR_HEIGHT; y++) {
                    Block block = level.getBlockState(anchor.offset(x, y, z)).getBlock();
                    if (block == Blocks.OAK_LOG || block == Blocks.BIRCH_LOG || block == Blocks.SPRUCE_LOG) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean isAllowedLog(String blockId) {
        return blockId.equals("minecraft:oak_log")
                || blockId.equals("minecraft:birch_log")
                || blockId.equals("minecraft:spruce_log");
    }

    /**
     * episode 初始世界的最小安全快照。
     */
    public record SafetySnapshot(
            Map<Long, String> blocks,
            int partnerTargetItems,
            int chestTargetItems,
            int targetBlocks
    ) {
    }

    /**
     * 独立终态判定器使用的世界观测值。
     */
    public record Observation(
            int partnerTargetItems,
            int chestTargetItems,
            int targetBlocks,
            int safetyViolations,
            boolean navigationDone,
            String executionState
    ) {
    }

    /**
     * 向命令层返回场景操作结果和可选上下文。
     */
    public record Result(
            boolean successful,
            String error,
            @Nullable Context context,
            @Nullable ExperimentScenario scenario
    ) {
        private static Result succeeded(
                ExperimentSessionRegistry.Context context,
                ExperimentScenario scenario
        ) {
            return new Result(true, "", context, scenario);
        }

        private static Result failed(String error) {
            return new Result(false, error, null, null);
        }
    }
}
