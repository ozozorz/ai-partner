package io.github.ozozorz.aipartner.workflow;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.control.MaidActionRegistry;
import io.github.ozozorz.aipartner.control.MaidActionRequest;
import io.github.ozozorz.aipartner.control.MaidControlDecision;
import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.control.MaidControlJsonCodec;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.event.MaidWorkflowLifecycleEvent;
import io.github.ozozorz.aipartner.core.task.MaidTaskRuntime;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Persistent server-side interpreter for a bounded sequence of semantic actions.
 * It advances only from verified receipts or terminal task contracts and never from model claims.
 */
public final class MaidWorkflowRuntime {
    private static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_EVIDENCE_ENTRIES = 16;
    private static final int MAX_EVIDENCE_LENGTH = 512;

    private final AiPartnerEntity partner;
    private final MaidTaskRuntime taskRuntime;
    private @Nullable UUID workflowId;
    private @Nullable UUID ownerId;
    private String sourceId = "";
    private String originalRequest = "";
    private List<MaidControlIntent> steps = List.of();
    private List<MaidControlIntent> pendingGoals = List.of();
    private @Nullable MaidWorkflowStatus status;
    private int stepIndex;
    private int maxReplans;
    private int replansUsed;
    private long deadlineEpochMillis;
    private @Nullable UUID activeTaskContractId;
    private FailureCode failureCode = FailureCode.NONE;
    private final List<String> evidence = new ArrayList<>();
    private boolean restoredReplanEventPending;

    public MaidWorkflowRuntime(AiPartnerEntity partner, MaidTaskRuntime taskRuntime) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
    }

    /** Admits a new bounded workflow and begins dispatching its first step. */
    public MaidWorkflowStartResult start(MaidWorkflowSpec spec, ServerPlayer actor) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(actor, "actor");
        if (!partner.isAlive()) {
            return MaidWorkflowStartResult.rejected(FailureCode.PARTNER_DIED, "partner_alive=false");
        }
        if (!partner.isOwnedBy(actor) || !spec.ownerId().equals(actor.getUUID())) {
            return MaidWorkflowStartResult.rejected(FailureCode.PERMISSION_DENIED, "actor_is_owner=false");
        }
        if (partner.level() != actor.level()) {
            return MaidWorkflowStartResult.rejected(FailureCode.DIFFERENT_DIMENSION, "same_dimension=false");
        }

        cancel(actor, "replaced_by_new_workflow");
        workflowId = spec.workflowId();
        ownerId = spec.ownerId();
        sourceId = spec.sourceId();
        originalRequest = spec.originalRequest();
        steps = spec.steps();
        pendingGoals = spec.steps();
        status = MaidWorkflowStatus.RUNNING;
        stepIndex = 0;
        maxReplans = spec.maxReplans();
        replansUsed = 0;
        deadlineEpochMillis = System.currentTimeMillis() + spec.timeoutSeconds() * 1000L;
        activeTaskContractId = null;
        failureCode = FailureCode.NONE;
        evidence.clear();
        restoredReplanEventPending = false;
        publish("workflow_started", actor, "steps=" + steps.size());
        advance(actor);
        return MaidWorkflowStartResult.success();
    }

    /** Polls the active task contract and advances only after its authoritative terminal result. */
    public void tick() {
        if (partner.level().isClientSide() || status == null || !status.isActive()) {
            return;
        }
        if (System.currentTimeMillis() > deadlineEpochMillis) {
            fail(FailureCode.TIMEOUT, "workflow_deadline_expired");
            return;
        }
        if (!partner.isAlive()) {
            fail(FailureCode.PARTNER_DIED, "partner_alive=false");
            return;
        }
        ServerPlayer actor = owner();
        if (actor == null) {
            fail(FailureCode.OWNER_OFFLINE, "owner_online=false");
            return;
        }
        if (partner.level() != actor.level()) {
            fail(FailureCode.DIFFERENT_DIMENSION, "same_dimension=false");
            return;
        }
        if (status == MaidWorkflowStatus.WAITING_REPLAN) {
            if (restoredReplanEventPending) {
                restoredReplanEventPending = false;
                publish("workflow_replan_requested", actor, lastEvidence());
            }
            return;
        }
        if (status == MaidWorkflowStatus.RUNNING) {
            advance(actor);
            return;
        }
        if (status != MaidWorkflowStatus.WAITING_ACTION || activeTaskContractId == null) {
            fail(FailureCode.INTERNAL_ERROR, "workflow_wait_state_invalid");
            return;
        }

        TaskContract contract = partner.getCurrentContract().orElse(null);
        if (contract == null || !activeTaskContractId.equals(contract.contractId())) {
            stepFailed(FailureCode.CANCELLED_BY_PLAYER, "active_task_contract_replaced");
            return;
        }
        if (contract.status() == ContractStatus.COMPLETED) {
            appendEvidence("step=" + stepIndex + ",task_contract=" + contract.contractId()
                    + ",result=COMPLETED,job=" + contract.job().type().name());
            publish("workflow_step_completed", actor, lastEvidence());
            markGoalCompleted(steps.get(stepIndex));
            activeTaskContractId = null;
            stepIndex++;
            status = MaidWorkflowStatus.RUNNING;
            advance(actor);
        } else if (contract.status() == ContractStatus.FAILED) {
            stepFailed(contract.failureCode(), "task_contract_failed=" + contract.failureCode().name());
        } else if (contract.status() == ContractStatus.CANCELLED) {
            stepFailed(contract.failureCode(), "task_contract_cancelled");
        }
    }

    /** Replaces the failed remainder after exactly one bounded server-authorized replan. */
    public boolean applyReplan(UUID expectedWorkflowId, List<MaidControlIntent> replacement, ServerPlayer actor) {
        if (!matches(expectedWorkflowId) || status != MaidWorkflowStatus.WAITING_REPLAN
                || ownerId == null || !ownerId.equals(actor.getUUID())) {
            return false;
        }
        MaidWorkflowSpec validated;
        try {
            validated = new MaidWorkflowSpec(
                    expectedWorkflowId,
                    ownerId,
                    sourceId,
                    originalRequest,
                    replacement,
                    maxReplans,
                    Math.max(1, (int) Math.min(
                            MaidWorkflowSpec.MAX_TIMEOUT_SECONDS,
                            (deadlineEpochMillis - System.currentTimeMillis()) / 1000L
                    ))
            );
        } catch (IllegalArgumentException exception) {
            fail(FailureCode.INVALID_PARAMETER, "replacement_plan_invalid");
            return false;
        }
        if (!MaidWorkflowSpec.preservesOrderedGoals(validated.steps(), pendingGoals)) {
            fail(FailureCode.CONTRACT_VIOLATION, "replacement_plan_dropped_required_goals");
            return false;
        }
        steps = validated.steps();
        stepIndex = 0;
        activeTaskContractId = null;
        status = MaidWorkflowStatus.RUNNING;
        appendEvidence("replan=" + replansUsed + ",replacement_steps=" + steps.size());
        publish("workflow_replanned", actor, lastEvidence());
        advance(actor);
        return true;
    }

    /** Fails a workflow whose asynchronous replan could not be produced or validated. */
    public void rejectReplan(UUID expectedWorkflowId, String detail) {
        if (matches(expectedWorkflowId) && status == MaidWorkflowStatus.WAITING_REPLAN) {
            fail(failureCode == FailureCode.NONE ? FailureCode.INTERNAL_ERROR : failureCode, detail);
        }
    }

    /** Cancels an active workflow and only the task contract owned by its current step. */
    public void cancel(@Nullable ServerPlayer actor, String reason) {
        if (status == null || !status.isActive()) {
            return;
        }
        MaidWorkflowStatus previous = status;
        status = MaidWorkflowStatus.CANCELLED;
        failureCode = FailureCode.CANCELLED_BY_PLAYER;
        appendEvidence("workflow_cancelled=" + bounded(reason, MAX_EVIDENCE_LENGTH));
        if (previous == MaidWorkflowStatus.WAITING_ACTION && ownsCurrentTaskContract()) {
            taskRuntime.cancel(actor, reason);
        }
        activeTaskContractId = null;
        publish("workflow_cancelled", actor, lastEvidence());
    }

    /** Cancels only the expected workflow so a stale asynchronous request cannot affect its replacement. */
    public void cancel(UUID expectedWorkflowId, @Nullable ServerPlayer actor, String reason) {
        if (matches(expectedWorkflowId)) {
            cancel(actor, reason);
        }
    }

    /** Returns true only while the given workflow may dispatch its current semantic step. */
    public boolean acceptsInvocation(UUID candidateWorkflowId) {
        return matches(candidateWorkflowId) && status == MaidWorkflowStatus.RUNNING;
    }

    public boolean hasActiveWorkflow() {
        return status != null && status.isActive();
    }

    public Optional<UUID> workflowId() {
        return Optional.ofNullable(workflowId);
    }

    public String summary() {
        if (workflowId == null || status == null) {
            return "NONE";
        }
        return status.name() + " step=" + Math.min(stepIndex + 1, steps.size()) + "/" + steps.size()
                + " replans=" + replansUsed + "/" + maxReplans;
    }

    /** Persists the complete workflow boundary, sequence, cursor, budget, and evidence. */
    public void save(ValueOutput output) {
        if (workflowId == null || ownerId == null || status == null) {
            return;
        }
        output.putInt("MaidWorkflowFormatVersion", FORMAT_VERSION);
        output.putString("MaidWorkflowId", workflowId.toString());
        output.putString("MaidWorkflowOwnerId", ownerId.toString());
        output.putString("MaidWorkflowSource", sourceId);
        output.putString("MaidWorkflowOriginalRequest", originalRequest);
        output.putString("MaidWorkflowStatus", status.name());
        output.putInt("MaidWorkflowStepIndex", stepIndex);
        output.putInt("MaidWorkflowMaxReplans", maxReplans);
        output.putInt("MaidWorkflowReplansUsed", replansUsed);
        output.putLong("MaidWorkflowDeadline", deadlineEpochMillis);
        output.putString("MaidWorkflowFailureCode", failureCode.name());
        if (activeTaskContractId != null) {
            output.putString("MaidWorkflowActiveTaskContract", activeTaskContractId.toString());
        }
        output.putInt("MaidWorkflowStepCount", steps.size());
        for (int index = 0; index < steps.size(); index++) {
            output.putString("MaidWorkflowStep" + index, MaidControlJsonCodec.encodeIntent(steps.get(index)));
        }
        output.putInt("MaidWorkflowPendingGoalCount", pendingGoals.size());
        for (int index = 0; index < pendingGoals.size(); index++) {
            output.putString(
                    "MaidWorkflowPendingGoal" + index,
                    MaidControlJsonCodec.encodeIntent(pendingGoals.get(index))
            );
        }
        output.putInt("MaidWorkflowEvidenceCount", evidence.size());
        for (int index = 0; index < evidence.size(); index++) {
            output.putString("MaidWorkflowEvidence" + index, evidence.get(index));
        }
    }

    /** Restores a workflow fail-closed; malformed plans are discarded and their task is cancelled. */
    public void load(ValueInput input) {
        clear();
        Optional<String> savedId = input.getString("MaidWorkflowId");
        if (savedId.isEmpty()) {
            return;
        }
        try {
            int loadedFormatVersion = input.getIntOr("MaidWorkflowFormatVersion", -1);
            if (loadedFormatVersion != FORMAT_VERSION && loadedFormatVersion != LEGACY_FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported workflow format");
            }
            UUID loadedId = UUID.fromString(savedId.get());
            UUID loadedOwner = UUID.fromString(input.getStringOr("MaidWorkflowOwnerId", ""));
            MaidWorkflowStatus loadedStatus = MaidWorkflowStatus.valueOf(input.getStringOr(
                    "MaidWorkflowStatus",
                    MaidWorkflowStatus.FAILED.name()
            ));
            int count = input.getIntOr("MaidWorkflowStepCount", -1);
            if (count < 1 || count > MaidWorkflowSpec.MAX_STEPS) {
                throw new IllegalArgumentException("invalid workflow step count");
            }
            List<MaidControlIntent> loadedSteps = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                loadedSteps.add(MaidControlJsonCodec.decodePersistedIntent(
                        input.getStringOr("MaidWorkflowStep" + index, "")
                ));
            }
            int loadedMaxReplans = input.getIntOr("MaidWorkflowMaxReplans", 0);
            new MaidWorkflowSpec(
                    loadedId,
                    loadedOwner,
                    input.getStringOr("MaidWorkflowSource", "UNKNOWN"),
                    input.getStringOr("MaidWorkflowOriginalRequest", ""),
                    loadedSteps,
                    loadedMaxReplans,
                    MaidWorkflowSpec.MAX_TIMEOUT_SECONDS
            );
            int loadedIndex = input.getIntOr("MaidWorkflowStepIndex", 0);
            if (loadedIndex < 0 || loadedIndex > loadedSteps.size()) {
                throw new IllegalArgumentException("invalid workflow cursor");
            }
            List<MaidControlIntent> loadedPendingGoals = loadedFormatVersion == FORMAT_VERSION
                    ? loadPendingGoals(input)
                    : List.copyOf(loadedSteps.subList(loadedIndex, loadedSteps.size()));
            if (!MaidWorkflowSpec.preservesOrderedGoals(
                    loadedSteps.subList(loadedIndex, loadedSteps.size()),
                    loadedPendingGoals
            )) {
                throw new IllegalArgumentException("persisted workflow dropped required goals");
            }

            workflowId = loadedId;
            ownerId = loadedOwner;
            sourceId = bounded(input.getStringOr("MaidWorkflowSource", "UNKNOWN"), 64);
            originalRequest = bounded(input.getStringOr("MaidWorkflowOriginalRequest", ""), 512);
            steps = List.copyOf(loadedSteps);
            pendingGoals = loadedPendingGoals;
            status = loadedStatus;
            stepIndex = loadedIndex;
            maxReplans = loadedMaxReplans;
            replansUsed = Math.max(0, Math.min(
                    maxReplans,
                    input.getIntOr("MaidWorkflowReplansUsed", 0)
            ));
            deadlineEpochMillis = input.getLongOr("MaidWorkflowDeadline", System.currentTimeMillis());
            failureCode = FailureCode.valueOf(input.getStringOr(
                    "MaidWorkflowFailureCode",
                    FailureCode.NONE.name()
            ));
            activeTaskContractId = input.getString("MaidWorkflowActiveTaskContract")
                    .map(UUID::fromString)
                    .orElse(null);
            int evidenceCount = input.getIntOr("MaidWorkflowEvidenceCount", 0);
            if (evidenceCount < 0 || evidenceCount > MAX_EVIDENCE_ENTRIES) {
                throw new IllegalArgumentException("invalid workflow evidence count");
            }
            for (int index = 0; index < evidenceCount; index++) {
                appendEvidence(input.getStringOr("MaidWorkflowEvidence" + index, ""));
            }
            restoredReplanEventPending = status == MaidWorkflowStatus.WAITING_REPLAN;
        } catch (IllegalArgumentException exception) {
            clear();
            taskRuntime.cancel(null, "invalid_persisted_workflow");
        }
    }

    private void advance(ServerPlayer actor) {
        while (status == MaidWorkflowStatus.RUNNING && stepIndex < steps.size()) {
            MaidControlIntent step = steps.get(stepIndex);
            publish("workflow_step_started", actor, "step=" + stepIndex + ",action="
                    + MaidActionRegistry.contractFor(step).actionKind());
            MaidControlDecision decision = MaidActionRegistry.execute(
                    partner,
                    actor,
                    step,
                    MaidActionRequest.workflow(originalRequest, workflowSourceId(), requireWorkflowId())
            );
            if (!decision.accepted()) {
                stepFailed(decision.failureCode(), decision.evidence());
                return;
            }
            if (decision.completed()) {
                appendEvidence("step=" + stepIndex + ",result=COMPLETED," + decision.evidence());
                publish("workflow_step_completed", actor, lastEvidence());
                markGoalCompleted(step);
                stepIndex++;
                continue;
            }
            activeTaskContractId = decision.relatedTaskContractId().orElse(null);
            if (activeTaskContractId == null) {
                stepFailed(FailureCode.INTERNAL_ERROR, "running_action_without_task_contract");
                return;
            }
            status = MaidWorkflowStatus.WAITING_ACTION;
            appendEvidence("step=" + stepIndex + ",result=RUNNING," + decision.evidence());
            return;
        }
        if (status == MaidWorkflowStatus.RUNNING && stepIndex >= steps.size()) {
            complete(actor);
        }
    }

    private void stepFailed(FailureCode code, String detail) {
        failureCode = code == FailureCode.NONE ? FailureCode.INTERNAL_ERROR : code;
        appendEvidence("step=" + stepIndex + ",result=FAILED,code=" + failureCode.name()
                + ",detail=" + bounded(detail, 240));
        activeTaskContractId = null;
        ServerPlayer actor = owner();
        if (replansUsed < maxReplans && sourceId.startsWith("LLM")) {
            replansUsed++;
            status = MaidWorkflowStatus.WAITING_REPLAN;
            publish("workflow_replan_requested", actor, lastEvidence());
            return;
        }
        fail(failureCode, detail);
    }

    private void complete(@Nullable ServerPlayer actor) {
        if (!pendingGoals.isEmpty()) {
            fail(
                    FailureCode.CONTRACT_VIOLATION,
                    "workflow_plan_exhausted_with_pending_goals=" + pendingGoals.size()
            );
            return;
        }
        status = MaidWorkflowStatus.COMPLETED;
        failureCode = FailureCode.NONE;
        activeTaskContractId = null;
        appendEvidence("workflow_result=COMPLETED");
        publish("workflow_completed", actor, lastEvidence());
    }

    private void fail(FailureCode code, String detail) {
        MaidWorkflowStatus previous = status;
        status = MaidWorkflowStatus.FAILED;
        failureCode = code == FailureCode.NONE ? FailureCode.INTERNAL_ERROR : code;
        appendEvidence("workflow_result=FAILED,code=" + failureCode.name()
                + ",detail=" + bounded(detail, 240));
        if (previous == MaidWorkflowStatus.WAITING_ACTION && ownsCurrentTaskContract()) {
            taskRuntime.cancel(owner(), "workflow_failed");
        }
        activeTaskContractId = null;
        publish("workflow_failed", owner(), lastEvidence());
    }

    private void publish(String event, @Nullable ServerPlayer actor, String detail) {
        if (workflowId == null || status == null) {
            return;
        }
        MaidDomainEvents.publish(new MaidWorkflowLifecycleEvent(
                event,
                sourceId,
                partner,
                actor,
                workflowId,
                status,
                stepIndex,
                steps.size(),
                replansUsed,
                failureCode,
                originalRequest,
                bounded(detail, MAX_EVIDENCE_LENGTH),
                List.copyOf(evidence)
        ));
    }

    private ServerPlayer owner() {
        if (!(partner.getOwner() instanceof ServerPlayer serverPlayer)
                || ownerId == null || !ownerId.equals(serverPlayer.getUUID())) {
            return null;
        }
        return serverPlayer;
    }

    private boolean ownsCurrentTaskContract() {
        return activeTaskContractId != null && partner.getCurrentContract()
                .map(contract -> activeTaskContractId.equals(contract.contractId()))
                .orElse(false);
    }

    private boolean matches(UUID candidate) {
        return workflowId != null && workflowId.equals(candidate);
    }

    private UUID requireWorkflowId() {
        if (workflowId == null) {
            throw new IllegalStateException("Workflow runtime has no active id");
        }
        return workflowId;
    }

    private String workflowSourceId() {
        return sourceId + "_WORKFLOW";
    }

    private void appendEvidence(String entry) {
        String boundedEntry = bounded(entry, MAX_EVIDENCE_LENGTH);
        if (boundedEntry.isBlank()) {
            return;
        }
        if (evidence.size() == MAX_EVIDENCE_ENTRIES) {
            evidence.removeFirst();
        }
        evidence.add(boundedEntry);
    }

    private String lastEvidence() {
        return evidence.isEmpty() ? "none" : evidence.getLast();
    }

    private void clear() {
        workflowId = null;
        ownerId = null;
        sourceId = "";
        originalRequest = "";
        steps = List.of();
        pendingGoals = List.of();
        status = null;
        stepIndex = 0;
        maxReplans = 0;
        replansUsed = 0;
        deadlineEpochMillis = 0L;
        activeTaskContractId = null;
        failureCode = FailureCode.NONE;
        evidence.clear();
        restoredReplanEventPending = false;
    }

    /** Removes one original obligation only after an equivalent semantic action really completed. */
    private void markGoalCompleted(MaidControlIntent completedAction) {
        if (pendingGoals.isEmpty() || !pendingGoals.getFirst().equals(completedAction)) {
            return;
        }
        pendingGoals = pendingGoals.size() == 1
                ? List.of()
                : List.copyOf(pendingGoals.subList(1, pendingGoals.size()));
    }

    private static List<MaidControlIntent> loadPendingGoals(ValueInput input) {
        int count = input.getIntOr("MaidWorkflowPendingGoalCount", -1);
        if (count < 0 || count > MaidWorkflowSpec.MAX_STEPS) {
            throw new IllegalArgumentException("invalid pending workflow goal count");
        }
        List<MaidControlIntent> loaded = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            loaded.add(MaidControlJsonCodec.decodePersistedIntent(
                    input.getStringOr("MaidWorkflowPendingGoal" + index, "")
            ));
        }
        return List.copyOf(loaded);
    }

    private static String bounded(String value, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
