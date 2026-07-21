package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.registry.ModContractValidators;
import net.minecraft.server.level.ServerPlayer;

/**
 * 执行跨任务通用验证，再把任务专属条件交给冻结的验证器注册表。
 */
public final class ContractCompiler {
    private ContractCompiler() {
    }

    /**
     * 先验证所有权、维度和参数形状，再生成正式契约。
     */
    public static ContractDecision compile(AiPartnerEntity partner, ServerPlayer player, JobSpec candidate) {
        if (!partner.isOwnedBy(player)) {
            return ContractDecision.rejected(FailureCode.PERMISSION_DENIED, "message.ai-partner.not_owner");
        }
        if (partner.level() != player.level()) {
            return ContractDecision.rejected(FailureCode.DIFFERENT_DIMENSION, "message.ai-partner.different_dimension");
        }

        TaskDefinition definition = TaskDefinitionRegistry.get(candidate.type());
        if (!definition.implemented()) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.unsupported_milestone");
        }
        if (!definition.acceptsShape(candidate)) {
            if (definition.targetRequired() && !definition.acceptsTarget(candidate.target())) {
                return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
            }
            return ContractDecision.rejected(FailureCode.INVALID_PARAMETER, "message.ai-partner.invalid_parameter");
        }
        return ModContractValidators.registry().validate(partner, player, candidate);
    }
}
