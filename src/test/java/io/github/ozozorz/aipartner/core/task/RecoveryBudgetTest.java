package io.github.ozozorz.aipartner.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证局部恢复预算在整个契约范围内累计、耗尽，并能安全地从存档计数恢复。
 */
class RecoveryBudgetTest {
    @Test
    void rejectsRecoveryAfterTotalBudgetIsConsumed() {
        RecoveryBudget budget = new RecoveryBudget();

        assertTrue(budget.tryConsume(2, true));
        assertTrue(budget.tryConsume(2, true));
        assertFalse(budget.tryConsume(2, true));
        assertEquals(2, budget.consumed());
    }

    @Test
    void disabledRecoveryDoesNotConsumeBudget() {
        RecoveryBudget budget = new RecoveryBudget();

        assertFalse(budget.tryConsume(2, false));
        assertFalse(budget.tryConsume(0, true));
        assertEquals(0, budget.consumed());
    }

    @Test
    void restoresAndResetsPersistedConsumption() {
        RecoveryBudget budget = new RecoveryBudget();
        budget.restore(2);

        assertFalse(budget.tryConsume(2, true));
        budget.reset();
        assertTrue(budget.tryConsume(2, true));
        assertEquals(1, budget.consumed());
    }
}
