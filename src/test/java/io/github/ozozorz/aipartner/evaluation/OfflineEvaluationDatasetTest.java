package io.github.ozozorz.aipartner.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 防止冻结数据集在调参期间被意外改成不同规模、类别或划分。
 */
class OfflineEvaluationDatasetTest {
    @Test
    void loadsFrozenBalancedDataset() {
        OfflineEvaluationDataset.Loaded dataset = OfflineEvaluationDataset.load();

        assertEquals("1.0", dataset.version());
        assertEquals(72, dataset.cases().size());
        assertEquals(64, dataset.sha256().length());
        assertEquals(Set.of("dev", "test"), dataset.cases().stream()
                .map(OfflineEvaluationCase::split)
                .collect(Collectors.toSet()));
        assertEquals(6, dataset.cases().stream()
                .map(OfflineEvaluationCase::category)
                .distinct()
                .count());
        assertTrue(dataset.cases().stream().allMatch(value -> value.instruction().chars().anyMatch(ch -> ch > 127)));
        assertTrue(dataset.cases().stream().allMatch(value -> value.goldDialogueAct() != null));
        assertNotNull(dataset.rawJsonl());
    }
}
