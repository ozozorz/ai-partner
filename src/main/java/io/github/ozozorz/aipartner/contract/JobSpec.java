package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.job.JobType;
import java.util.Objects;

/**
 * 经过语法解析后的候选任务；其中不包含逐 tick 动作或可改写的安全规则。
 *
 * @param type 任务类型
 * @param target 方块或物品标识；基础移动任务为空字符串
 * @param quantity 数量；不适用时为 0
 * @param radius 搜索半径；不适用时为 0
 */
public record JobSpec(JobType type, String target, int quantity, int radius) {
    public JobSpec {
        Objects.requireNonNull(type, "type");
        target = target == null ? "" : target;
    }

    /**
     * 创建不带目标参数的基础任务。
     */
    public static JobSpec basic(JobType type) {
        return new JobSpec(type, "", 0, 0);
    }
}

