package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerPlayer;

/**
 * 验证一种 JobSpec 的任务专属前置条件并生成正式契约。
 */
@FunctionalInterface
public interface TaskContractValidator {
    ContractDecision validate(AiPartnerEntity partner, ServerPlayer player, JobSpec candidate);
}
