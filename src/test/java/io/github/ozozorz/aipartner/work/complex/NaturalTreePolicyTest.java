package io.github.ozozorz.aipartner.work.complex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证自然树识别宁可漏判，也不会批准微型木柱、建筑木件或带方块实体的结构。 */
class NaturalTreePolicyTest {
    @Test
    void acceptsBoundedTreeWithRootsAndCanopy() {
        assertTrue(NaturalTreePolicy.accepts(summary(5, 10, 5, 4, 1, false, false)));
    }

    @Test
    void rejectsWoodenStructuresAndBlockEntities() {
        assertFalse(NaturalTreePolicy.accepts(summary(5, 10, 5, 4, 1, false, true)));
        assertFalse(NaturalTreePolicy.accepts(summary(5, 10, 5, 4, 1, true, false)));
    }

    @Test
    void rejectsUndersizedOrUnrootedLogClusters() {
        assertFalse(NaturalTreePolicy.accepts(summary(2, 10, 5, 4, 1, false, false)));
        assertFalse(NaturalTreePolicy.accepts(summary(5, 10, 5, 4, 0, false, false)));
        assertFalse(NaturalTreePolicy.accepts(summary(5, 3, 2, 4, 1, false, false)));
    }

    private static NaturalTreePolicy.Summary summary(
            int logs,
            int leaves,
            int topLeaves,
            int height,
            int roots,
            boolean blockEntity,
            boolean playerWood
    ) {
        return new NaturalTreePolicy.Summary(
                logs,
                leaves,
                topLeaves,
                height,
                roots,
                blockEntity,
                playerWood
        );
    }
}
