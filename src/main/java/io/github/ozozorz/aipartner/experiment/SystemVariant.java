package io.github.ozozorz.aipartner.experiment;

import java.util.Locale;
import java.util.Optional;

/**
 * 定义论文比较系统及 A2 消融的固定能力开关，避免用散落的字符串隐式改变实验条件。
 */
public enum SystemVariant {
    RULE_BT(false, true, true, true),
    LLM_SCHEMA(true, false, false, false),
    MAID_IBC(true, true, true, true),
    MAID_IBC_A2_NO_RUNTIME_MONITORING(true, true, false, false);

    private final boolean usesLlm;
    private final boolean semanticValidationEnabled;
    private final boolean runtimeMonitoringEnabled;
    private final boolean localRecoveryEnabled;

    SystemVariant(
            boolean usesLlm,
            boolean semanticValidationEnabled,
            boolean runtimeMonitoringEnabled,
            boolean localRecoveryEnabled
    ) {
        this.usesLlm = usesLlm;
        this.semanticValidationEnabled = semanticValidationEnabled;
        this.runtimeMonitoringEnabled = runtimeMonitoringEnabled;
        this.localRecoveryEnabled = localRecoveryEnabled;
    }

    public boolean usesLlm() {
        return usesLlm;
    }

    public boolean semanticValidationEnabled() {
        return semanticValidationEnabled;
    }

    public boolean runtimeMonitoringEnabled() {
        return runtimeMonitoringEnabled;
    }

    public boolean localRecoveryEnabled() {
        return localRecoveryEnabled;
    }

    /**
     * 接受命令中常用的连字符、下划线和简写形式。
     */
    public static Optional<SystemVariant> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("A2".equals(normalized) || "MAID_IBC_A2".equals(normalized)) {
            return Optional.of(MAID_IBC_A2_NO_RUNTIME_MONITORING);
        }
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
