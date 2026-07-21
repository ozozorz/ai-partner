package io.github.ozozorz.aipartner.work.complex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证熔炉在处理中间态、完成态和玩家干预后的批次守恒。 */
class FurnaceBatchConservationTest {
    @Test
    void acceptsIntermediateAndCompletedBatchStates() {
        assertTrue(FurnaceBatchConservation.respects(8, 4, 10, 2));
        assertTrue(FurnaceBatchConservation.respects(0, 20, 10, 2));
    }

    @Test
    void rejectsForeignCountsAndInvalidResultRatios() {
        assertFalse(FurnaceBatchConservation.respects(9, 4, 10, 2));
        assertFalse(FurnaceBatchConservation.respects(8, 3, 10, 2));
        assertFalse(FurnaceBatchConservation.respects(10, 0, 10, 0));
    }
}
