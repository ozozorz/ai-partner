package io.github.ozozorz.aipartner.experiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 固定复合期望终态和“完成后恢复”判定规则。
 */
class ExperimentScenarioJudgeTest {
    @Test
    void retryOutcomeRequiresObservedRecovery() {
        assertFalse(ExperimentScenarioJudge.matchesExpected("COMPLETED_AFTER_RETRY", "COMPLETED", 0));
        assertTrue(ExperimentScenarioJudge.matchesExpected("COMPLETED_AFTER_RETRY", "COMPLETED", 1));
    }

    @Test
    void unreachableScenarioAcceptsRegisteredFailureAlternatives() {
        assertTrue(ExperimentScenarioJudge.matchesExpected("TARGET_NOT_FOUND_OR_TIMEOUT", "TARGET_NOT_FOUND", 0));
        assertTrue(ExperimentScenarioJudge.matchesExpected("TARGET_NOT_FOUND_OR_TIMEOUT", "PATH_UNREACHABLE", 0));
        assertTrue(ExperimentScenarioJudge.matchesExpected("TARGET_NOT_FOUND_OR_TIMEOUT", "TIMEOUT", 0));
        assertFalse(ExperimentScenarioJudge.matchesExpected("TARGET_NOT_FOUND_OR_TIMEOUT", "COMPLETED", 0));
    }

    @Test
    void removedTargetAcceptsSearchAndActionLayerFailureCodes() {
        assertTrue(ExperimentScenarioJudge.matchesExpected(
                "TARGET_NOT_FOUND_OR_DISAPPEARED",
                "TARGET_NOT_FOUND",
                0
        ));
        assertTrue(ExperimentScenarioJudge.matchesExpected(
                "TARGET_NOT_FOUND_OR_DISAPPEARED",
                "TARGET_DISAPPEARED",
                0
        ));
        assertFalse(ExperimentScenarioJudge.matchesExpected(
                "TARGET_NOT_FOUND_OR_DISAPPEARED",
                "COMPLETED",
                0
        ));
    }

    @Test
    void cancelledTaskUsesExecutorStateInsteadOfAmbientNavigation() {
        ExperimentScenarioService.Observation idleButRoaming =
                new ExperimentScenarioService.Observation(0, 0, 0, 0, false, "IDLE");
        ExperimentScenarioService.Observation executorStillRunning =
                new ExperimentScenarioService.Observation(0, 0, 0, 0, true, "NAVIGATE");

        assertTrue(ExperimentScenarioJudge.taskExecutionStopped(idleButRoaming));
        assertFalse(ExperimentScenarioJudge.taskExecutionStopped(executorStillRunning));
    }
}
