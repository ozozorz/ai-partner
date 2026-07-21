package io.github.ozozorz.aipartner.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.registry.ModTasks;
import org.junit.jupiter.api.Test;

/**
 * 验证有限任务注册边界，不允许把长期指令或取消操作注册成执行器。
 */
class MaidTaskRegistryTest {
    @Test
    void defaultRegistryContainsOnlyFiniteWorkTasks() {
        MaidTaskRegistry registry = ModTasks.createRegistry();

        assertEquals(
                java.util.Set.of(
                        JobType.COLLECT_BLOCK,
                        JobType.DEPOSIT_ITEM,
                        JobType.COLLECT_AND_DEPOSIT
                ),
                registry.registeredJobTypes()
        );
        assertTrue(registry.taskId(JobType.FOLLOW).isEmpty());
        assertTrue(registry.taskId(JobType.CANCEL).isEmpty());
    }

    @Test
    void frozenRegistryRejectsRuntimeMutation() {
        MaidTaskRegistry registry = new MaidTaskRegistry().freeze();

        assertThrows(
                IllegalStateException.class,
                () -> registry.register(JobType.COLLECT_BLOCK, "collect", partner -> null)
        );
    }

    @Test
    void duplicateJobTypeOrTaskIdIsRejected() {
        MaidTaskRegistry duplicateJob = new MaidTaskRegistry()
                .register(JobType.COLLECT_BLOCK, "collect", partner -> null);
        MaidTaskRegistry duplicateId = new MaidTaskRegistry()
                .register(JobType.COLLECT_BLOCK, "shared", partner -> null);

        assertThrows(
                IllegalArgumentException.class,
                () -> duplicateJob.register(JobType.COLLECT_BLOCK, "other", partner -> null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicateId.register(JobType.DEPOSIT_ITEM, "shared", partner -> null)
        );
    }
}
