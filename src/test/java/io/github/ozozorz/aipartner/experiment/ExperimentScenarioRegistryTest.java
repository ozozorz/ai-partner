package io.github.ozozorz.aipartner.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * 验证游戏内实验场景清单的规模、ID 唯一性与扰动覆盖。
 */
class ExperimentScenarioRegistryTest {
    @Test
    void exposesEighteenFrozenScenarios() {
        assertEquals(18, ExperimentScenarioRegistry.all().size());
        assertEquals(18, new HashSet<>(ExperimentScenarioRegistry.ids()).size());
        assertTrue(ExperimentScenarioRegistry.all().stream()
                .allMatch(value -> !value.instruction().isBlank() && !value.expectedOutcome().isBlank()));
    }

    @Test
    void includesThreeRuntimeDisturbanceScenarios() {
        assertEquals(3, ExperimentScenarioRegistry.all().stream()
                .filter(value -> value.disturbance() != ExperimentScenario.Disturbance.NONE)
                .count());
    }
}
