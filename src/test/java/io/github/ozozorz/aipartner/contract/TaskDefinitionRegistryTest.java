package io.github.ozozorz.aipartner.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * 验证所有 JobType 都有明确且冻结的服务器能力定义。
 */
class TaskDefinitionRegistryTest {
    @Test
    void everyJobTypeHasDefinition() {
        assertEquals(JobType.values().length, TaskDefinitionRegistry.all().size());
    }

    @Test
    void currentMilestoneCapabilitiesAreExplicit() {
        assertTrue(TaskDefinitionRegistry.get(JobType.FOLLOW).implemented());
        assertTrue(TaskDefinitionRegistry.get(JobType.STAY).implemented());
        assertTrue(TaskDefinitionRegistry.get(JobType.COLLECT_BLOCK).implemented());
        assertTrue(TaskDefinitionRegistry.get(JobType.DEPOSIT_ITEM).implemented());
        assertTrue(TaskDefinitionRegistry.get(JobType.CANCEL).implemented());
        assertTrue(TaskDefinitionRegistry.get(JobType.COLLECT_AND_DEPOSIT).implemented());
    }

    @Test
    void collectDefinitionRejectsNonWhitelistedTarget() {
        TaskDefinition definition = TaskDefinitionRegistry.get(JobType.COLLECT_BLOCK);

        assertFalse(definition.acceptsShape(new JobSpec(
                JobType.COLLECT_BLOCK,
                "minecraft:diamond_block",
                8,
                16
        )));
    }
}
