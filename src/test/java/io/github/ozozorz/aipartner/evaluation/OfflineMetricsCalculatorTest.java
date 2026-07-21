package io.github.ozozorz.aipartner.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 用小型手算样例固定六项离线指标的分母和槽位宏 F1 定义。
 */
class OfflineMetricsCalculatorTest {
    @Test
    void calculatesRegisteredMetrics() {
        JobSpec collect = new JobSpec(JobType.COLLECT_BLOCK, "minecraft:oak_log", 8, 16);
        List<OfflineModelPrediction> predictions = List.of(
                prediction("p1", DialogueAct.PROPOSE_JOB, JobType.COLLECT_BLOCK, "minecraft:oak_log", 8, 16,
                        false, false, DialogueAct.PROPOSE_JOB, collect),
                prediction("p2", DialogueAct.ASK_CLARIFICATION, null, null, null, null,
                        true, false, DialogueAct.ASK_CLARIFICATION, null),
                prediction("p3", DialogueAct.REJECT_UNSUPPORTED, null, null, null, null,
                        false, true, DialogueAct.REJECT_UNSUPPORTED, null),
                prediction("p4", DialogueAct.PROPOSE_JOB, JobType.COLLECT_BLOCK, "minecraft:oak_log", 8, 16,
                        false, false, DialogueAct.REJECT_UNSUPPORTED, null)
        );

        OfflineMetricsCalculator.Metrics metrics = OfflineMetricsCalculator.calculate(
                "test-run",
                "1.0",
                "hash",
                4,
                predictions,
                1.0,
                0.5
        );

        assertEquals(1.0, metrics.jvr(), 1.0e-9);
        assertEquals(0.75, metrics.intentAccuracy(), 1.0e-9);
        assertEquals(2.0 / 3.0, metrics.slotMacroF1(), 1.0e-9);
        assertEquals(1.0, metrics.ccr(), 1.0e-9);
        assertEquals(1.0, metrics.urr(), 1.0e-9);
        assertEquals(0.5, metrics.frr(), 1.0e-9);
        assertEquals(0.75, metrics.exactMatchAccuracy(), 1.0e-9);
    }

    private static OfflineModelPrediction prediction(
            String id,
            DialogueAct goldAct,
            JobType goldType,
            String goldTarget,
            Integer goldQuantity,
            Integer goldRadius,
            boolean shouldClarify,
            boolean shouldReject,
            DialogueAct predictedAct,
            JobSpec predictedJob
    ) {
        return new OfflineModelPrediction(
                id,
                "test",
                "category",
                "instruction",
                goldAct,
                goldType,
                goldTarget,
                goldQuantity,
                goldRadius,
                shouldClarify,
                shouldReject,
                true,
                predictedAct,
                predictedJob,
                null,
                "model",
                "prompt",
                1,
                10L,
                10,
                10,
                0.01,
                "{}"
        );
    }
}
