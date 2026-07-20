package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.job.JobType;
import java.util.Set;

/**
 * 描述服务器内置任务能力和参数边界；模型不能创建或改写此定义。
 */
public record TaskDefinition(
        JobType type,
        boolean implemented,
        boolean targetRequired,
        int minimumQuantity,
        int maximumQuantity,
        int minimumRadius,
        int maximumRadius,
        Set<String> allowedTargets
) {
    public TaskDefinition {
        allowedTargets = Set.copyOf(allowedTargets);
        if (minimumQuantity < 0
                || maximumQuantity < minimumQuantity
                || minimumRadius < 0
                || maximumRadius < minimumRadius) {
            throw new IllegalArgumentException("Invalid TaskDefinition bounds for " + type);
        }
    }

    /**
     * 检查候选任务的字段形状和有界数值，不读取动态世界状态。
     */
    public boolean acceptsShape(JobSpec candidate) {
        if (candidate.type() != type) {
            return false;
        }
        if (!targetRequired) {
            return candidate.target().isEmpty() && candidate.quantity() == 0 && candidate.radius() == 0;
        }
        return allowedTargets.contains(candidate.target())
                && candidate.quantity() >= minimumQuantity
                && candidate.quantity() <= maximumQuantity
                && candidate.radius() >= minimumRadius
                && candidate.radius() <= maximumRadius;
    }
}

