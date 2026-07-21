package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/**
 * 任务专属验证器注册表，避免 ContractCompiler 随功能数量持续膨胀。
 */
public final class TaskContractValidatorRegistry {
    private final Map<JobType, TaskContractValidator> validators = new EnumMap<>(JobType.class);
    private boolean frozen;

    public TaskContractValidatorRegistry register(JobType jobType, TaskContractValidator validator) {
        if (frozen) {
            throw new IllegalStateException("Contract validator registry is frozen");
        }
        TaskContractValidator previous = validators.putIfAbsent(
                Objects.requireNonNull(jobType, "jobType"),
                Objects.requireNonNull(validator, "validator")
        );
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate validator for " + jobType);
        }
        return this;
    }

    public TaskContractValidatorRegistry freeze() {
        frozen = true;
        return this;
    }

    /**
     * 调用对应验证器；未注册任务以结构化原因拒绝。
     */
    public ContractDecision validate(
            AiPartnerEntity partner,
            ServerPlayer player,
            JobSpec candidate
    ) {
        TaskContractValidator validator = validators.get(candidate.type());
        return validator == null
                ? ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.unsupported_milestone")
                : validator.validate(partner, player, candidate);
    }

    public Set<JobType> registeredJobTypes() {
        return Collections.unmodifiableSet(validators.keySet());
    }
}
