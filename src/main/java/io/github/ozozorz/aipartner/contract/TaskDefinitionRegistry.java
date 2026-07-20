package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 保存 Job DSL 的唯一任务定义表，确保所有系统变体共享相同执行能力。
 */
public final class TaskDefinitionRegistry {
    private static final Map<JobType, TaskDefinition> DEFINITIONS = createDefinitions();

    private TaskDefinitionRegistry() {
    }

    /**
     * 返回指定类型的不可变任务定义。
     */
    public static TaskDefinition get(JobType type) {
        TaskDefinition definition = DEFINITIONS.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("No TaskDefinition registered for " + type);
        }
        return definition;
    }

    /**
     * 返回用于启动检查和测试的完整注册表副本。
     */
    public static Map<JobType, TaskDefinition> all() {
        return Map.copyOf(DEFINITIONS);
    }

    private static Map<JobType, TaskDefinition> createDefinitions() {
        EnumMap<JobType, TaskDefinition> definitions = new EnumMap<>(JobType.class);
        definitions.put(JobType.FOLLOW, parameterless(JobType.FOLLOW, true));
        definitions.put(JobType.STAY, parameterless(JobType.STAY, true));
        definitions.put(JobType.CANCEL, parameterless(JobType.CANCEL, true));
        Set<String> allowedLogs = Set.copyOf(AllowedTargets.suggestedBlockIds());
        definitions.put(JobType.COLLECT_BLOCK, bounded(
                JobType.COLLECT_BLOCK,
                true,
                AllowedTargets.MAX_COLLECT_RADIUS,
                allowedLogs
        ));
        definitions.put(JobType.DEPOSIT_ITEM, bounded(
                JobType.DEPOSIT_ITEM,
                true,
                ContainerTargets.MAX_DEPOSIT_RADIUS,
                allowedLogs
        ));
        definitions.put(JobType.COLLECT_AND_DEPOSIT, bounded(
                JobType.COLLECT_AND_DEPOSIT,
                false,
                AllowedTargets.MAX_COLLECT_RADIUS,
                allowedLogs
        ));
        return definitions;
    }

    private static TaskDefinition parameterless(JobType type, boolean implemented) {
        return new TaskDefinition(type, implemented, false, 0, 0, 0, 0, Set.of());
    }

    private static TaskDefinition bounded(
            JobType type,
            boolean implemented,
            int maximumRadius,
            Set<String> allowedTargets
    ) {
        return new TaskDefinition(type, implemented, true, 1, 64, 1, maximumRadius, allowedTargets);
    }
}

