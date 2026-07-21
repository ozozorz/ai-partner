package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.core.task.MaidTaskRegistry;
import io.github.ozozorz.aipartner.gameplay.task.CollectAndDepositMaidTask;
import io.github.ozozorz.aipartner.gameplay.task.CollectBlockMaidTask;
import io.github.ozozorz.aipartner.gameplay.task.DepositItemMaidTask;
import io.github.ozozorz.aipartner.job.JobType;

/**
 * v0.5 有限任务的组合根；实体只接收冻结后的通用注册表。
 */
public final class ModTasks {
    private ModTasks() {
    }

    /**
     * 为一个女仆运行时创建独立、冻结的任务注册表。
     */
    public static MaidTaskRegistry createRegistry() {
        return new MaidTaskRegistry()
                .register(JobType.COLLECT_BLOCK, CollectBlockMaidTask.ID, CollectBlockMaidTask::new)
                .register(JobType.DEPOSIT_ITEM, DepositItemMaidTask.ID, DepositItemMaidTask::new)
                .register(
                        JobType.COLLECT_AND_DEPOSIT,
                        CollectAndDepositMaidTask.ID,
                        CollectAndDepositMaidTask::new
                )
                .freeze();
    }
}
