package io.github.ozozorz.aipartner.work.complex;

import io.github.ozozorz.aipartner.work.MaidWorkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 只批准已暴露、具有安全水平站位且周围无岩浆的原版矿石，明确不负责开隧道。
 */
public final class MiningSafety {
    private MiningSafety() {
    }

    public static boolean isSupportedOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.EMERALD_ORES)
                || state.is(BlockTags.DIAMOND_ORES);
    }

    public static boolean isSafeExposedTarget(MaidWorkContext context, BlockPos orePosition) {
        BlockState ore = context.level().getBlockState(orePosition);
        if (!isSupportedOre(ore)
                || ore.getDestroySpeed(context.level(), orePosition) < 0.0F
                || context.level().getBlockEntity(orePosition) != null
                || hasNearbyLava(context, orePosition)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = orePosition.relative(direction);
            if (context.isLegal(stand)
                    && isPassable(context, stand)
                    && isPassable(context, stand.above())
                    && context.level().getBlockState(stand.below()).isFaceSturdy(
                            context.level(),
                            stand.below(),
                            Direction.UP
                    )
                    && !hasNearbyLava(context, stand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPassable(MaidWorkContext context, BlockPos position) {
        BlockState state = context.level().getBlockState(position);
        return !state.is(Blocks.FIRE)
                && !state.is(Blocks.SOUL_FIRE)
                && state.getFluidState().isEmpty()
                && state.getCollisionShape(context.level(), position).isEmpty();
    }

    private static boolean hasNearbyLava(MaidWorkContext context, BlockPos center) {
        if (context.level().getFluidState(center).is(FluidTags.LAVA)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (context.level().getFluidState(center.relative(direction)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
