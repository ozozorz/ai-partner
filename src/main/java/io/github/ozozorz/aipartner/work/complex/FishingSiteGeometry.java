package io.github.ozozorz.aipartner.work.complex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 定义开放水域中心、岸边支撑块与女仆站立点之间的纯几何关系。
 * 岸边位置表示实体脚部所在的空气格，因此必须比水面高一格。
 */
public final class FishingSiteGeometry {
    private FishingSiteGeometry() {
    }

    /** 根据水面中心和水平方向计算岸块上方的实体站立位置。 */
    public static BlockPos bankStandPosition(BlockPos water, Direction direction, int distance) {
        if (direction.getAxis().isVertical() || distance < 1) {
            throw new IllegalArgumentException("Fishing bank direction must be horizontal and distance positive");
        }
        return water.relative(direction, distance).above();
    }

    /** 检查站位是否位于同一直线、比水面高一格且水平距离处于允许范围。 */
    public static boolean isAligned(
            BlockPos water,
            BlockPos bankStand,
            int minimumDistance,
            int maximumDistance
    ) {
        int deltaX = bankStand.getX() - water.getX();
        int deltaZ = bankStand.getZ() - water.getZ();
        if (bankStand.getY() != water.getY() + 1 || deltaX != 0 && deltaZ != 0) {
            return false;
        }
        int distance = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
        return distance >= minimumDistance && distance <= maximumDistance;
    }

    /** 返回从水面中心指向岸边的第 step 个水面通道格。 */
    public static BlockPos lanePosition(BlockPos water, BlockPos bankStand, int step) {
        int stepX = Integer.signum(bankStand.getX() - water.getX());
        int stepZ = Integer.signum(bankStand.getZ() - water.getZ());
        return water.offset(stepX * step, 0, stepZ * step);
    }

    /** 返回水面中心到岸边站位的水平格数。 */
    public static int horizontalDistance(BlockPos water, BlockPos bankStand) {
        return Math.max(
                Math.abs(bankStand.getX() - water.getX()),
                Math.abs(bankStand.getZ() - water.getZ())
        );
    }
}
