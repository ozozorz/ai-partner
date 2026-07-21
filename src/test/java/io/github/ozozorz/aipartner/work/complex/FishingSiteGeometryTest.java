package io.github.ozozorz.aipartner.work.complex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** 验证钓鱼站位始终位于真实岸块上方，而不是会被水流占据的水面同层空气格。 */
class FishingSiteGeometryTest {
    @Test
    void bankStandPositionIsOneBlockAboveTheShoreSupport() {
        BlockPos water = new BlockPos(10, 64, 20);

        BlockPos bankStand = FishingSiteGeometry.bankStandPosition(water, Direction.NORTH, 3);

        assertEquals(new BlockPos(10, 65, 17), bankStand);
        assertEquals(new BlockPos(10, 64, 17), bankStand.below());
        assertTrue(FishingSiteGeometry.isAligned(water, bankStand, 3, 6));
    }

    @Test
    void sameHeightDiagonalAndOutOfRangePositionsAreRejected() {
        BlockPos water = new BlockPos(0, 64, 0);

        assertFalse(FishingSiteGeometry.isAligned(water, new BlockPos(0, 64, 3), 3, 6));
        assertFalse(FishingSiteGeometry.isAligned(water, new BlockPos(3, 65, 3), 3, 6));
        assertFalse(FishingSiteGeometry.isAligned(water, new BlockPos(0, 65, 7), 3, 6));
    }

    @Test
    void laneStaysOnTheWaterSurfaceWhileHeadingTowardRaisedBank() {
        BlockPos water = new BlockPos(4, 70, 4);
        BlockPos bankStand = FishingSiteGeometry.bankStandPosition(water, Direction.EAST, 4);

        assertEquals(new BlockPos(5, 70, 4), FishingSiteGeometry.lanePosition(water, bankStand, 1));
        assertEquals(new BlockPos(7, 70, 4), FishingSiteGeometry.lanePosition(water, bankStand, 3));
        assertEquals(4, FishingSiteGeometry.horizontalDistance(water, bankStand));
    }
}
