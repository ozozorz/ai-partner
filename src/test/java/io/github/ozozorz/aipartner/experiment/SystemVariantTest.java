package io.github.ozozorz.aipartner.experiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 固定四种实验条件的模块开关，防止 A2 或 B1 在重构中悄悄恢复监控能力。
 */
class SystemVariantTest {
    @Test
    void schemaBaselineSkipsSemanticValidationAndRuntimeRecovery() {
        assertTrue(SystemVariant.LLM_SCHEMA.usesLlm());
        assertFalse(SystemVariant.LLM_SCHEMA.semanticValidationEnabled());
        assertFalse(SystemVariant.LLM_SCHEMA.runtimeMonitoringEnabled());
        assertFalse(SystemVariant.LLM_SCHEMA.localRecoveryEnabled());
    }

    @Test
    void a2KeepsSemanticValidationButRemovesRuntimeModules() {
        assertTrue(SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING.semanticValidationEnabled());
        assertFalse(SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING.runtimeMonitoringEnabled());
        assertFalse(SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING.localRecoveryEnabled());
        assertSame(
                SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING,
                SystemVariant.parse("A2").orElseThrow()
        );
    }
}
