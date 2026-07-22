package io.github.ozozorz.aipartner.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证任务超时只恢复剩余预算，并能在 GUI 暂停期间保持剩余 tick 不变。
 */
class GameTimeDeadlineTest {
    @Test
    void newBudgetExpiresAtDeclaredDeadline() {
        GameTimeDeadline deadline = new GameTimeDeadline();
        deadline.start(100L, 20L);

        assertFalse(deadline.isExpired(119L));
        assertTrue(deadline.isExpired(120L));
    }

    @Test
    void restoreUsesRemainingInsteadOfFullBudget() {
        GameTimeDeadline deadline = new GameTimeDeadline();
        deadline.restore(1_000L, 1_800L, 25L);

        assertEquals(25L, deadline.remainingTicks(1_000L));
        assertTrue(deadline.isExpired(1_025L));
    }

    @Test
    void restoreClampsCorruptOversizedValue() {
        GameTimeDeadline deadline = new GameTimeDeadline();
        deadline.restore(50L, 100L, Long.MAX_VALUE);

        assertEquals(100L, deadline.remainingTicks(50L));
    }

    @Test
    void pausePreservesRemainingBudgetAcrossWorldTick() {
        GameTimeDeadline deadline = new GameTimeDeadline();
        deadline.start(100L, 20L);
        deadline.pauseOneTick();

        assertEquals(20L, deadline.remainingTicks(101L));
    }
}
