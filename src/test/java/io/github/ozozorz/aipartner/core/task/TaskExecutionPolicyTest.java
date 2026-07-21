package io.github.ozozorz.aipartner.core.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证核心执行策略能够迁移旧实验标签而不依赖实验枚举。
 */
class TaskExecutionPolicyTest {
    @Test
    void completeVariantsKeepMonitoringAndRecovery() {
        TaskExecutionPolicy policy = TaskExecutionPolicy.fromLegacySource("MAID_IBC");

        assertTrue(policy.runtimeMonitoringEnabled());
        assertTrue(policy.localRecoveryEnabled());
    }

    @Test
    void schemaAndA2VariantsDisableRuntimeCapabilities() {
        TaskExecutionPolicy schema = TaskExecutionPolicy.fromLegacySource("LLM_SCHEMA");
        TaskExecutionPolicy a2 = TaskExecutionPolicy.fromLegacySource("maid-ibc-a2-no-runtime-monitoring");

        assertFalse(schema.runtimeMonitoringEnabled());
        assertFalse(schema.localRecoveryEnabled());
        assertFalse(a2.runtimeMonitoringEnabled());
        assertFalse(a2.localRecoveryEnabled());
    }

    @Test
    void missingLegacySourceFallsBackToRuleBaseline() {
        TaskExecutionPolicy policy = TaskExecutionPolicy.fromLegacySource(null);

        assertTrue(policy.runtimeMonitoringEnabled());
        assertTrue(policy.localRecoveryEnabled());
        org.junit.jupiter.api.Assertions.assertEquals("RULE_BT", policy.sourceId());
    }
}
