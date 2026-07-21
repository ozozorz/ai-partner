package io.github.ozozorz.aipartner.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * B1 只比较结构约束，不得意外复用 IBC 的目标白名单或世界前置条件验证。
 */
class SchemaOnlyContractCompilerTest {
    @Test
    void acceptsStructurallyValidCandidateWithoutSemanticWorldChecks() {
        ContractDecision decision = SchemaOnlyContractCompiler.compile(
                new JobSpec(JobType.COLLECT_BLOCK, "minecraft:diamond_block", 8, 16)
        );

        assertTrue(decision.accepted());
    }
}
