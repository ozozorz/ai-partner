package io.github.ozozorz.aipartner.core.task;

/**
 * 一次任务提交携带的执行能力开关。
 *
 * <p>核心只理解能力，不依赖论文实验的 SystemVariant 枚举。</p>
 */
public record TaskExecutionPolicy(
        String sourceId,
        boolean runtimeMonitoringEnabled,
        boolean localRecoveryEnabled
) {
    public static final TaskExecutionPolicy DEFAULT = new TaskExecutionPolicy("RULE_BT", true, true);

    public TaskExecutionPolicy {
        sourceId = sourceId == null || sourceId.isBlank() ? "UNKNOWN" : sourceId.strip();
    }

    /**
     * 为普通命令、GUI 或自然语言入口创建完整运行策略。
     */
    public static TaskExecutionPolicy standard(String sourceId) {
        return new TaskExecutionPolicy(sourceId, true, true);
    }

    /**
     * 仅用于迁移 v0.4 存档中保存的实验变体字符串。
     */
    public static TaskExecutionPolicy fromLegacySource(String sourceId) {
        String checkedSource = sourceId == null || sourceId.isBlank() ? "RULE_BT" : sourceId.strip();
        String normalized = checkedSource.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "LLM_SCHEMA", "MAID_IBC_A2_NO_RUNTIME_MONITORING" ->
                    new TaskExecutionPolicy(checkedSource, false, false);
            default -> standard(checkedSource);
        };
    }
}
