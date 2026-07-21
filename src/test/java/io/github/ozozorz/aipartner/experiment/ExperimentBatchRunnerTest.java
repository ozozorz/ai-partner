package io.github.ozozorz.aipartner.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.experiment.ExperimentBatchStore.PlanItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证预实验顺序以及最低/理想主实验规模不会因命令重构而改变。
 */
class ExperimentBatchRunnerTest {
    @Test
    void pretestRunsAllRuleEpisodesBeforeSmallLlmSamples() {
        List<PlanItem> plan = ExperimentBatchRunner.createPretestPlan();

        assertEquals(28, plan.size());
        assertTrue(plan.subList(0, 18).stream().allMatch(item -> item.systemVariant().equals("RULE_BT")));
        assertEquals(5, plan.stream().filter(item -> item.systemVariant().equals("LLM_SCHEMA")).count());
        assertEquals(5, plan.stream().filter(item -> item.systemVariant().equals("MAID_IBC")).count());
    }

    @Test
    void mainPlansHaveRequiredEpisodeCountsAndBalancedVariants() {
        List<PlanItem> minimum = ExperimentBatchRunner.createMainPlan(3);
        List<PlanItem> ideal = ExperimentBatchRunner.createMainPlan(5);

        assertEquals(162, minimum.size());
        assertEquals(270, ideal.size());
        for (SystemVariant variant : List.of(
                SystemVariant.RULE_BT,
                SystemVariant.LLM_SCHEMA,
                SystemVariant.MAID_IBC
        )) {
            assertEquals(54, minimum.stream().filter(item -> item.systemVariant().equals(variant.name())).count());
            assertEquals(90, ideal.stream().filter(item -> item.systemVariant().equals(variant.name())).count());
        }
    }

    @Test
    void a2PlanCoversAllFrozenScenarios() {
        assertEquals(
                18,
                ExperimentBatchRunner.buildSingleVariantPlan(
                        SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING,
                        1
                ).size()
        );
    }
}
