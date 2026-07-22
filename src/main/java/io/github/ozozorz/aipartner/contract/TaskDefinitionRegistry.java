package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 保存有限任务的唯一能力定义表，确保所有输入入口共享相同执行边界。
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
        definitions.put(JobType.FOLLOW, parameterless(JobType.FOLLOW));
        definitions.put(JobType.STAY, parameterless(JobType.STAY));
        definitions.put(JobType.CANCEL, parameterless(JobType.CANCEL));
        Set<String> allowedLogs = Set.copyOf(AllowedTargets.suggestedBlockIds());
        definitions.put(JobType.COLLECT_BLOCK, bounded(
                JobType.COLLECT_BLOCK,
                AllowedTargets.MAX_COLLECT_RADIUS,
                allowedLogs
        ));
        definitions.put(JobType.DEPOSIT_ITEM, bounded(
                JobType.DEPOSIT_ITEM,
                ContainerTargets.MAX_DEPOSIT_RADIUS,
                allowedLogs
        ));
        definitions.put(JobType.COLLECT_AND_DEPOSIT, bounded(
                JobType.COLLECT_AND_DEPOSIT,
                AllowedTargets.MAX_COLLECT_RADIUS,
                allowedLogs
        ));
        definitions.put(JobType.TRANSFER_ITEM, bounded(
                JobType.TRANSFER_ITEM,
                ContainerTargets.MAX_DEPOSIT_RADIUS,
                Set.of("*")
        ));
        return definitions;
    }

    private static TaskDefinition parameterless(JobType type) {
        return new TaskDefinition(type, false, 0, 0, 0, 0, Set.of());
    }

    private static TaskDefinition bounded(
            JobType type,
            int maximumRadius,
            Set<String> allowedTargets
    ) {
        return new TaskDefinition(type, true, 1, 64, 1, maximumRadius, allowedTargets);
    }
}
