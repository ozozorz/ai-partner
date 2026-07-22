package io.github.ozozorz.aipartner.core.event;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.workflow.MaidWorkflowStatus;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Immutable evidence event emitted as a bounded workflow advances or reaches a terminal state. */
public record MaidWorkflowLifecycleEvent(
        String event,
        String sourceId,
        AiPartnerEntity partner,
        @Nullable ServerPlayer actor,
        UUID workflowId,
        MaidWorkflowStatus status,
        int stepIndex,
        int stepCount,
        int replansUsed,
        FailureCode failureCode,
        String originalRequest,
        String detail,
        List<String> evidence
) implements MaidDomainEvent {
    public MaidWorkflowLifecycleEvent {
        evidence = List.copyOf(evidence);
    }
}
