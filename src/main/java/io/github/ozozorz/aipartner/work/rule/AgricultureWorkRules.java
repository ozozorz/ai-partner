package io.github.ozozorz.aipartner.work.rule;

import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import io.github.ozozorz.aipartner.work.MaidWorkContext;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.work.MaidWorkRule;
import io.github.ozozorz.aipartner.work.WorkActionResult;
import io.github.ozozorz.aipartner.work.WorkTarget;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 构造普通作物、甘蔗、瓜果、可可、花草和除雪六类原版农业规则。
 */
public final class AgricultureWorkRules {
    private AgricultureWorkRules() {
    }

    public static Collection<MaidWorkRule> create() {
        return List.of(
                new CropRule(),
                new SugarCaneRule(),
                new MelonRule(),
                new CocoaRule(),
                new ForagerRule(),
                new SnowRule()
        );
    }

    /** 收获成熟普通作物并消耗一份对应种子原位补种，也会填补空耕地。 */
    private static final class CropRule extends BlockRule {
        private CropRule() {
            super(MaidWorkMode.FARMER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            Item seed = seedForMatureCrop(state);
            if (seed != null) {
                return context.actions().inventory().contains(seed)
                        && context.actions().inventory().hasAnySpace();
            }
            return state.isAir() && plantForSoil(context, position) != null;
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            BlockPos position = target.fallbackPosition();
            BlockState state = context.level().getBlockState(position);
            Item seed = seedForMatureCrop(state);
            if (seed != null) {
                BlockState replanted = state.getBlock() instanceof CropBlock crop
                        ? crop.getStateForAge(0)
                        : state.setValue(NetherWartBlock.AGE, 0);
                return context.actions().harvestBlock().harvestAndReplant(
                        context.level(),
                        position,
                        state,
                        replanted,
                        stack -> stack.is(seed)
                ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
            }

            PlantChoice choice = plantForSoil(context, position);
            if (choice == null) {
                return WorkActionResult.RETRY;
            }
            ItemStack consumed = context.actions().inventory().takeOne(choice.seed());
            if (consumed.isEmpty()) {
                return WorkActionResult.BLOCKED;
            }
            if (!context.actions().placeBlock().place(context.level(), position, choice.state())) {
                context.actions().inventory().add(consumed);
                return WorkActionResult.RETRY;
            }
            return WorkActionResult.SUCCESS;
        }

        private static Item seedForMatureCrop(BlockState state) {
            Block block = state.getBlock();
            if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                if (state.is(Blocks.WHEAT)) {
                    return Items.WHEAT_SEEDS;
                }
                if (state.is(Blocks.CARROTS)) {
                    return Items.CARROT;
                }
                if (state.is(Blocks.POTATOES)) {
                    return Items.POTATO;
                }
                if (state.is(Blocks.BEETROOTS)) {
                    return Items.BEETROOT_SEEDS;
                }
            }
            return state.is(Blocks.NETHER_WART)
                    && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE
                    ? Items.NETHER_WART
                    : null;
        }

        private static PlantChoice plantForSoil(MaidWorkContext context, BlockPos position) {
            if (!context.level().getBlockState(position).isAir()) {
                return null;
            }
            BlockState soil = context.level().getBlockState(position.below());
            if (soil.is(Blocks.FARMLAND)) {
                if (context.actions().inventory().contains(Items.WHEAT_SEEDS)) {
                    return new PlantChoice(Items.WHEAT_SEEDS, Blocks.WHEAT.defaultBlockState());
                }
                if (context.actions().inventory().contains(Items.CARROT)) {
                    return new PlantChoice(Items.CARROT, Blocks.CARROTS.defaultBlockState());
                }
                if (context.actions().inventory().contains(Items.POTATO)) {
                    return new PlantChoice(Items.POTATO, Blocks.POTATOES.defaultBlockState());
                }
                if (context.actions().inventory().contains(Items.BEETROOT_SEEDS)) {
                    return new PlantChoice(Items.BEETROOT_SEEDS, Blocks.BEETROOTS.defaultBlockState());
                }
            }
            if (soil.is(BlockTags.SUPPORTS_NETHER_WART)
                    && context.actions().inventory().contains(Items.NETHER_WART)) {
                return new PlantChoice(Items.NETHER_WART, Blocks.NETHER_WART.defaultBlockState());
            }
            return null;
        }
    }

    /** 甘蔗只破坏根部以上的茎，并能在合法水边表面补种新的根部。 */
    private static final class SugarCaneRule extends BlockRule {
        private SugarCaneRule() {
            super(MaidWorkMode.SUGAR_CANE);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            if (state.is(Blocks.SUGAR_CANE)) {
                return context.level().getBlockState(position.below()).is(Blocks.SUGAR_CANE)
                        && context.actions().inventory().hasAnySpace();
            }
            return state.isAir()
                    && context.actions().inventory().contains(Items.SUGAR_CANE)
                    && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(context.level(), position);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            BlockPos position = target.fallbackPosition();
            BlockState state = context.level().getBlockState(position);
            if (state.is(Blocks.SUGAR_CANE)
                    && context.level().getBlockState(position.below()).is(Blocks.SUGAR_CANE)) {
                return context.actions().breakBlock().destroyWithDrops(context.level(), position)
                        ? WorkActionResult.SUCCESS
                        : WorkActionResult.RETRY;
            }
            ItemStack cane = context.actions().inventory().takeOne(Items.SUGAR_CANE);
            if (cane.isEmpty()) {
                return WorkActionResult.BLOCKED;
            }
            if (!context.actions().placeBlock().place(
                    context.level(),
                    position,
                    Blocks.SUGAR_CANE.defaultBlockState()
            )) {
                context.actions().inventory().add(cane);
                return WorkActionResult.RETRY;
            }
            return WorkActionResult.SUCCESS;
        }
    }

    /** 只收获与成熟附着茎相连的南瓜或西瓜，永不破坏茎。 */
    private static final class MelonRule extends BlockRule {
        private MelonRule() {
            super(MaidWorkMode.MELON);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN))
                    && isAttachedFruit(context, position)
                    && context.actions().inventory().hasAnySpace();
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            return context.actions().breakBlock().destroyWithDrops(context.level(), target.fallbackPosition())
                    ? WorkActionResult.SUCCESS
                    : WorkActionResult.RETRY;
        }

        private static boolean isAttachedFruit(MaidWorkContext context, BlockPos fruitPosition) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockState stem = context.level().getBlockState(fruitPosition.relative(direction));
                if (stem.getBlock() instanceof AttachedStemBlock
                        && stem.getValue(AttachedStemBlock.FACING) == direction.getOpposite()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 收获成熟可可并消耗一颗可可豆补种，也能在受支持原木侧面种植。 */
    private static final class CocoaRule extends BlockRule {
        private CocoaRule() {
            super(MaidWorkMode.COCOA);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            if (state.is(Blocks.COCOA) && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE) {
                return context.actions().inventory().contains(Items.COCOA_BEANS)
                        && context.actions().inventory().hasAnySpace();
            }
            return state.isAir()
                    && context.actions().inventory().contains(Items.COCOA_BEANS)
                    && cocoaPlantState(context, position).isPresent();
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            BlockPos position = target.fallbackPosition();
            BlockState state = context.level().getBlockState(position);
            if (state.is(Blocks.COCOA) && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE) {
                return context.actions().harvestBlock().harvestAndReplant(
                        context.level(),
                        position,
                        state,
                        state.setValue(CocoaBlock.AGE, 0),
                        stack -> stack.is(Items.COCOA_BEANS)
                ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
            }
            Optional<BlockState> plantedState = cocoaPlantState(context, position);
            ItemStack bean = context.actions().inventory().takeOne(Items.COCOA_BEANS);
            if (plantedState.isEmpty() || bean.isEmpty()) {
                context.actions().inventory().add(bean);
                return WorkActionResult.BLOCKED;
            }
            if (!context.actions().placeBlock().place(context.level(), position, plantedState.get())) {
                context.actions().inventory().add(bean);
                return WorkActionResult.RETRY;
            }
            return WorkActionResult.SUCCESS;
        }

        private static Optional<BlockState> cocoaPlantState(MaidWorkContext context, BlockPos position) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockState candidate = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, direction);
                if (candidate.canSurvive(context.level(), position)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    /** 采集原版草、蕨和花，掉落完全交给当前主手与方块战利品表。 */
    private static final class ForagerRule extends BlockRule {
        private ForagerRule() {
            super(MaidWorkMode.FORAGER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return context.actions().inventory().hasAnySpace()
                    && (state.is(BlockTags.FLOWERS)
                    || state.is(Blocks.SHORT_GRASS)
                    || state.is(Blocks.TALL_GRASS)
                    || state.is(Blocks.FERN)
                    || state.is(Blocks.LARGE_FERN));
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            return context.actions().breakBlock().destroyWithDrops(context.level(), target.fallbackPosition())
                    ? WorkActionResult.SUCCESS
                    : WorkActionResult.RETRY;
        }
    }

    /** 仅在拥有锹时清除雪层或雪块，并按原版工具规则消耗耐久。 */
    private static final class SnowRule extends BlockRule {
        private SnowRule() {
            super(MaidWorkMode.SNOW_CLEARER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK))
                    && hasShovel(context)
                    && context.actions().inventory().hasAnySpace();
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            EquipmentLease lease = EquipmentLease.acquire(
                    context.partner(),
                    stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.SHOVELS)
            ).orElse(null);
            if (lease == null) {
                return WorkActionResult.BLOCKED;
            }
            try (lease) {
                return context.actions().breakBlock().destroyWithDrops(
                        context.level(),
                        target.fallbackPosition()
                ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
            }
        }

        private static boolean hasShovel(MaidWorkContext context) {
            return context.partner().getMainHandItem().typeHolder().is(ItemTags.SHOVELS)
                    || context.actions().inventory().contains(
                            stack -> !stack.isEmpty() && stack.typeHolder().is(ItemTags.SHOVELS)
                    );
        }
    }

    private abstract static class BlockRule implements MaidWorkRule {
        private final MaidWorkMode mode;

        private BlockRule(MaidWorkMode mode) {
            this.mode = mode;
        }

        @Override
        public final MaidWorkMode mode() {
            return mode;
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            if (target.isEntity()) {
                return false;
            }
            BlockPos position = target.fallbackPosition();
            return matchesBlock(context, position, context.level().getBlockState(position));
        }
    }

    private record PlantChoice(Item seed, BlockState state) {
    }
}
