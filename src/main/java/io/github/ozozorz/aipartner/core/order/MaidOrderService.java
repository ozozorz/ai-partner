package io.github.ozozorz.aipartner.core.order;

import io.github.ozozorz.aipartner.contract.ContractCompiler;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.event.OrderValidationEvent;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/**
 * 命令、GUI 和自然语言入口共用的服务端订单入口。
 */
public final class MaidOrderService {
    private MaidOrderService() {
    }

    /**
     * 使用完整语义验证编译候选任务，并在接受后立即调度。
     */
    public static ContractDecision submit(
            AiPartnerEntity partner,
            ServerPlayer actor,
            JobSpec candidate,
            String rawInstruction,
            TaskExecutionPolicy policy
    ) {
        ContractDecision decision = ContractCompiler.compile(partner, actor, candidate);
        return submitValidated(partner, actor, candidate, rawInstruction, decision, policy);
    }

    /**
     * 调度已经由指定实验协议验证的结果，并统一发布验证事件。
     */
    public static ContractDecision submitValidated(
            AiPartnerEntity partner,
            ServerPlayer actor,
            JobSpec candidate,
            String rawInstruction,
            ContractDecision decision,
            TaskExecutionPolicy policy
    ) {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(policy, "policy");

        MaidDomainEvents.publish(new OrderValidationEvent(
                policy.sourceId(),
                partner,
                actor,
                rawInstruction,
                candidate,
                decision,
                decision.failureCode().name()
        ));
        if (decision.accepted()) {
            if (decision.contract() == null) {
                throw new IllegalStateException("Accepted contract decision has no contract");
            }
            partner.applyValidatedContract(decision.contract(), actor, rawInstruction, policy);
        }
        return decision;
    }
}
