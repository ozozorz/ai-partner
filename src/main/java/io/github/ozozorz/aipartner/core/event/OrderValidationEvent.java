package io.github.ozozorz.aipartner.core.event;

import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一个结构化订单经过服务端验证后的结果。
 */
public record OrderValidationEvent(
        String sourceId,
        AiPartnerEntity partner,
        ServerPlayer actor,
        String rawInstruction,
        JobSpec candidate,
        ContractDecision decision,
        String outcome
) implements MaidDomainEvent {
}
