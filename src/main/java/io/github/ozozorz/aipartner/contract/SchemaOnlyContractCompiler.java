package io.github.ozozorz.aipartner.contract;

import java.util.List;

/**
 * 为 B1 LLM-Schema 创建仅通过 JSON Schema 的任务，不执行 IBC 世界语义前置条件验证。
 * 确定性执行器仍保留不可消融的动作级安全边界，模型不能直接修改世界。
 */
public final class SchemaOnlyContractCompiler {
    private SchemaOnlyContractCompiler() {
    }

    /**
     * 直接接受已由 JobSpecJsonCodec 严格解码的候选任务。
     */
    public static ContractDecision compile(JobSpec candidate) {
        TaskContract contract = TaskContract.accepted(
                candidate,
                List.of("json_schema_valid"),
                List.of("executor_reported_terminal_outcome"),
                List.of(
                        "server_action_whitelist_is_enforced",
                        "do_not_attack_friendly_entities",
                        "do_not_modify_outside_task_boundary"
                ),
                TaskContract.FailurePolicy.DEFAULT
        );
        return ContractDecision.accepted(contract);
    }
}
