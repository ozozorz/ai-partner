package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.contract.ContractCompiler;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Canonical semantic-action registry used by commands, menu buttons, local language control,
 * and LLM workflows. Every mutation is admitted and verified against the same IBC declaration.
 */
public final class MaidActionRegistry {
    private static final List<MaidContractPredicate> COMMON_PRECONDITIONS = List.of(
            MaidContractPredicate.PARTNER_ALIVE,
            MaidContractPredicate.ACTOR_IS_OWNER,
            MaidContractPredicate.SAME_DIMENSION,
            MaidContractPredicate.PARAMETERS_WITHIN_BOUNDARY
    );
    private static final List<MaidContractPredicate> COMMON_INVARIANTS = List.of(
            MaidContractPredicate.OWNER_AND_DIMENSION_PRESERVED
    );
    private static final Map<Class<?>, MaidActionContract> CONTRACTS = Map.ofEntries(
            entry(MaidControlIntent.RunTask.class, "RUN_TASK", MaidActionCompletion.RUNNING,
                    MaidContractPredicate.TASK_CONTRACT_ACCEPTED),
            entry(MaidControlIntent.SetWorkMode.class, "SET_WORK_MODE", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.SetSchedule.class, "SET_SCHEDULE", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.SetCombatPolicy.class, "SET_COMBAT_POLICY", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.ReturnHome.class, "RETURN_HOME", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.ConfigureLocation.class, "CONFIGURE_LOCATION", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.SetHomeBound.class, "SET_HOME_BOUND", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.SetRadius.class, "SET_RADIUS", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.Rename.class, "RENAME", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.REQUESTED_STATE_OBSERVED),
            entry(MaidControlIntent.QueryStatus.class, "QUERY_STATUS", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.QUERY_RESULT_OBSERVED),
            entry(MaidControlIntent.QueryInventory.class, "QUERY_INVENTORY", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.QUERY_RESULT_OBSERVED),
            entry(MaidControlIntent.RetrieveInventory.class, "RETRIEVE_INVENTORY", MaidActionCompletion.COMPLETED,
                    MaidContractPredicate.INVENTORY_TRANSFER_OBSERVED)
    );

    private MaidActionRegistry() {
    }

    /** Returns the immutable IBC declarations, including every permitted intent subtype. */
    public static Map<Class<?>, MaidActionContract> contracts() {
        return CONTRACTS;
    }

    /** Returns the contract declaration selected for a concrete typed intent. */
    public static MaidActionContract contractFor(MaidControlIntent intent) {
        MaidActionContract contract = CONTRACTS.get(Objects.requireNonNull(intent, "intent").getClass());
        if (contract == null) {
            throw new IllegalArgumentException("Unregistered maid action " + intent.getClass().getName());
        }
        return contract;
    }

    /**
     * Applies exactly one semantic action and verifies its postcondition before producing a receipt.
     * Rejections before dispatch never mutate maid state.
     */
    public static MaidControlDecision execute(
            AiPartnerEntity partner,
            ServerPlayer actor,
            MaidControlIntent intent,
            MaidActionRequest request
    ) {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        MaidActionContract actionContract = contractFor(intent);

        MaidControlDecision rejected = verifyAdmission(partner, actor, intent, actionContract);
        if (rejected != null) {
            return rejected;
        }

        if (request.workflowId() != null) {
            if (!partner.acceptsWorkflowInvocation(request.workflowId())) {
                return MaidControlDecision.rejected(
                        Component.translatable("message.ai-partner.failed", FailureCode.CANCELLED_BY_PLAYER.name()),
                        FailureCode.CANCELLED_BY_PLAYER,
                        "workflow_invocation_not_current"
                );
            }
        }

        ContractDecision preparedTask = null;
        if (intent instanceof MaidControlIntent.RunTask runTask) {
            preparedTask = ContractCompiler.compile(partner, actor, runTask.job());
            if (!preparedTask.accepted()) {
                return rejectedTaskDecision(preparedTask);
            }
        }

        if (request.workflowId() == null && !isReadOnly(intent)) {
            partner.interruptActiveWorkflow(actor, "external_action:" + request.sourceId());
        }

        MaidControlDecision result = switch (intent) {
            case MaidControlIntent.RunTask runTask -> runTask(
                    partner,
                    actor,
                    runTask.job(),
                    request,
                    Objects.requireNonNull(preparedTask, "preparedTask")
            );
            case MaidControlIntent.SetWorkMode setWorkMode -> {
                partner.setWorkMode(setWorkMode.mode());
                yield MaidControlDecision.completed(Component.translatable(
                        "message.ai-partner.work_mode_set",
                        setWorkMode.mode().serializedName()
                ), "work_mode=" + setWorkMode.mode().serializedName());
            }
            case MaidControlIntent.SetSchedule setSchedule -> {
                partner.setScheduleType(setSchedule.schedule());
                yield MaidControlDecision.completed(Component.translatable(
                        "message.ai-partner.schedule_set",
                        setSchedule.schedule().name()
                ), "schedule=" + setSchedule.schedule().name());
            }
            case MaidControlIntent.SetCombatPolicy setCombatPolicy -> {
                partner.setCombatPolicy(setCombatPolicy.policy());
                yield MaidControlDecision.completed(Component.translatable(
                        "message.ai-partner.combat_policy_set",
                        setCombatPolicy.policy().serializedName()
                ), "combat_policy=" + setCombatPolicy.policy().serializedName());
            }
            case MaidControlIntent.ReturnHome ignored -> {
                partner.requestReturnHome(actor);
                yield MaidControlDecision.completed(
                        Component.translatable("message.ai-partner.returning_home"),
                        "manual_directive=RETURN_HOME"
                );
            }
            case MaidControlIntent.ConfigureLocation configureLocation -> {
                if (configureLocation.clear()) {
                    partner.clearActivityLocation(configureLocation.location());
                } else {
                    partner.setActivityLocation(configureLocation.location());
                }
                yield MaidControlDecision.completed(Component.translatable(
                        configureLocation.clear()
                                ? "message.ai-partner.location_cleared"
                                : "message.ai-partner.location_set",
                        configureLocation.location().name()
                ), "location_" + configureLocation.location().name().toLowerCase()
                        + "=" + (configureLocation.clear() ? "cleared" : "configured"));
            }
            case MaidControlIntent.SetHomeBound setHomeBound -> {
                partner.setHomeBound(setHomeBound.enabled());
                yield MaidControlDecision.completed(Component.translatable(
                        "message.ai-partner.home_bound_set",
                        setHomeBound.enabled()
                ), "home_bound=" + setHomeBound.enabled());
            }
            case MaidControlIntent.SetRadius setRadius -> {
                partner.setActivityRadius(setRadius.radius());
                yield MaidControlDecision.completed(
                        Component.translatable("message.ai-partner.radius_set", setRadius.radius()),
                        "activity_radius=" + setRadius.radius()
                );
            }
            case MaidControlIntent.Rename rename -> {
                partner.setCustomName(Component.literal(rename.name()));
                partner.setCustomNameVisible(true);
                yield MaidControlDecision.completed(
                        Component.translatable("message.ai-partner.renamed", rename.name()),
                        "name=" + rename.name()
                );
            }
            case MaidControlIntent.QueryStatus ignored -> MaidControlDecision.completed(
                    status(partner),
                    statusEvidence(partner)
            );
            case MaidControlIntent.QueryInventory ignored -> MaidControlDecision.completed(
                    Component.translatable("message.ai-partner.inventory", partner.inventorySummary()),
                    "inventory=" + partner.inventorySummary()
            );
            case MaidControlIntent.RetrieveInventory ignored -> retrieveInventory(partner, actor);
        };

        if (result.accepted() && (!verifyGoal(partner, intent, result)
                || !verifyInvariant(partner, actor, actionContract))) {
            return MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.failed", FailureCode.INTERNAL_ERROR.name()),
                    FailureCode.INTERNAL_ERROR,
                    "action_postcondition_failed:" + actionContract.actionKind()
            );
        }
        return result;
    }

    private static MaidControlDecision verifyAdmission(
            AiPartnerEntity partner,
            ServerPlayer actor,
            MaidControlIntent intent,
            MaidActionContract actionContract
    ) {
        for (MaidContractPredicate predicate : actionContract.preconditions()) {
            MaidControlDecision violation = verifyPrecondition(predicate, partner, actor, intent);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    private static MaidControlDecision verifyPrecondition(
            MaidContractPredicate predicate,
            AiPartnerEntity partner,
            ServerPlayer actor,
            MaidControlIntent intent
    ) {
        return switch (predicate) {
            case PARTNER_ALIVE -> partner.isAlive() ? null : MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.conversation.target_unavailable"),
                    FailureCode.PARTNER_DIED,
                    "partner_alive=false"
            );
            case ACTOR_IS_OWNER -> partner.isOwnedBy(actor) ? null : MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.not_owner"),
                    FailureCode.PERMISSION_DENIED,
                    "actor_is_owner=false"
            );
            case SAME_DIMENSION -> partner.level() == actor.level() ? null : MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.different_dimension"),
                    FailureCode.DIFFERENT_DIMENSION,
                    "same_dimension=false"
            );
            case PARAMETERS_WITHIN_BOUNDARY -> verifyParameterBoundary(intent);
            case TASK_CONTRACT_ACCEPTED, REQUESTED_STATE_OBSERVED, QUERY_RESULT_OBSERVED,
                 INVENTORY_TRANSFER_OBSERVED, OWNER_AND_DIMENSION_PRESERVED ->
                    throw new IllegalStateException("Goal or invariant predicate declared as a precondition: " + predicate);
        };
    }

    private static MaidControlDecision verifyParameterBoundary(MaidControlIntent intent) {
        if (intent instanceof MaidControlIntent.SetRadius setRadius) {
            int maximum = MaidGameplayConfig.get().maximumActivityRadius();
            if (setRadius.radius() < 1 || setRadius.radius() > maximum) {
                return MaidControlDecision.rejected(
                        Component.translatable("message.ai-partner.control.invalid_radius", maximum),
                        FailureCode.INVALID_PARAMETER,
                        "radius_out_of_range"
                );
            }
        }
        if (intent instanceof MaidControlIntent.Rename rename
                && (rename.name().isEmpty()
                || rename.name().codePointCount(0, rename.name().length()) > 32
                || rename.name().chars().anyMatch(Character::isISOControl))) {
            return MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.invalid_name"),
                    FailureCode.INVALID_PARAMETER,
                    "name_out_of_range"
            );
        }
        return null;
    }

    private static MaidControlDecision runTask(
            AiPartnerEntity partner,
            ServerPlayer actor,
            JobSpec job,
            MaidActionRequest request,
            ContractDecision preparedDecision
    ) {
        if (!preparedDecision.accepted() || preparedDecision.contract() == null) {
            return rejectedTaskDecision(preparedDecision);
        }
        TaskContract contract = preparedDecision.contract();
        partner.applyValidatedContract(
                contract,
                actor,
                request.rawInstruction(),
                request.sourceId()
        );
        Component message = Component.translatable(responseKey(job.type()));
        String evidence = "task_contract=" + contract.contractId() + ",status=" + contract.status().name();
        if (contract.status() == io.github.ozozorz.aipartner.contract.ContractStatus.FAILED
                || contract.status() == io.github.ozozorz.aipartner.contract.ContractStatus.CANCELLED) {
            return MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.failed", contract.failureCode().name()),
                    contract.failureCode(),
                    evidence
            );
        }
        if (isImmediateDirective(job.type())
                || contract.status() == io.github.ozozorz.aipartner.contract.ContractStatus.COMPLETED) {
            return MaidControlDecision.completed(message, evidence, contract.contractId());
        }
        return MaidControlDecision.running(message, evidence, contract.contractId());
    }

    private static MaidControlDecision rejectedTaskDecision(ContractDecision decision) {
        return MaidControlDecision.rejected(
                Component.translatable(decision.messageKey()),
                decision.failureCode(),
                "task_contract_rejected=" + decision.failureCode().name()
        );
    }

    private static MaidControlDecision retrieveInventory(AiPartnerEntity partner, ServerPlayer actor) {
        int returned = partner.returnInventoryTo(actor);
        if (returned < 0) {
            return MaidControlDecision.rejected(
                    Component.translatable("message.ai-partner.inventory_busy"),
                    FailureCode.INVALID_PARAMETER,
                    "inventory_transfer=busy"
            );
        }
        return MaidControlDecision.completed(
                Component.translatable("message.ai-partner.inventory_returned", returned),
                "inventory_items_returned=" + returned
        );
    }

    private static boolean verifyGoal(
            AiPartnerEntity partner,
            MaidControlIntent intent,
            MaidControlDecision result
    ) {
        MaidActionContract contract = contractFor(intent);
        for (MaidContractPredicate goal : contract.goals()) {
            if (!evaluateGoal(goal, partner, intent, result)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateGoal(
            MaidContractPredicate goal,
            AiPartnerEntity partner,
            MaidControlIntent intent,
            MaidControlDecision result
    ) {
        return switch (goal) {
            case TASK_CONTRACT_ACCEPTED -> result.relatedTaskContractId().isPresent()
                    && partner.getCurrentContract()
                    .map(contract -> contract.contractId().equals(result.relatedTaskContractId().orElseThrow()))
                    .orElse(false);
            case REQUESTED_STATE_OBSERVED -> requestedStateObserved(partner, intent);
            case QUERY_RESULT_OBSERVED -> !result.evidence().isBlank();
            case INVENTORY_TRANSFER_OBSERVED ->
                    result.evidence().startsWith("inventory_items_returned=");
            case PARTNER_ALIVE, ACTOR_IS_OWNER, SAME_DIMENSION, PARAMETERS_WITHIN_BOUNDARY,
                 OWNER_AND_DIMENSION_PRESERVED ->
                    throw new IllegalStateException("Precondition or invariant declared as a goal: " + goal);
        };
    }

    private static boolean requestedStateObserved(AiPartnerEntity partner, MaidControlIntent intent) {
        return switch (intent) {
            case MaidControlIntent.RunTask ignored -> false;
            case MaidControlIntent.SetWorkMode setWorkMode -> partner.getWorkMode() == setWorkMode.mode();
            case MaidControlIntent.SetSchedule setSchedule -> partner.getScheduleType() == setSchedule.schedule();
            case MaidControlIntent.SetCombatPolicy setCombatPolicy ->
                    partner.getCombatPolicy() == setCombatPolicy.policy();
            case MaidControlIntent.ReturnHome ignored -> partner.getManualDirective() == ManualDirective.RETURN_HOME;
            case MaidControlIntent.ConfigureLocation configureLocation -> configureLocation.clear()
                    ? partner.getActivityLocation(configureLocation.location()).isEmpty()
                    : partner.getActivityLocation(configureLocation.location()).isPresent();
            case MaidControlIntent.SetHomeBound setHomeBound ->
                    partner.isHomeBound() == setHomeBound.enabled();
            case MaidControlIntent.SetRadius setRadius -> partner.getActivityRadius() == setRadius.radius();
            case MaidControlIntent.Rename rename -> partner.getCustomName() != null
                    && rename.name().equals(partner.getCustomName().getString());
            case MaidControlIntent.QueryStatus ignored -> false;
            case MaidControlIntent.QueryInventory ignored -> false;
            case MaidControlIntent.RetrieveInventory ignored -> false;
        };
    }

    private static boolean verifyInvariant(
            AiPartnerEntity partner,
            ServerPlayer actor,
            MaidActionContract actionContract
    ) {
        for (MaidContractPredicate invariant : actionContract.invariants()) {
            if (invariant != MaidContractPredicate.OWNER_AND_DIMENSION_PRESERVED
                    || !partner.isAlive()
                    || !partner.isOwnedBy(actor)
                    || partner.level() != actor.level()) {
                return false;
            }
        }
        return true;
    }

    private static Map.Entry<Class<?>, MaidActionContract> entry(
            Class<?> intentType,
            String kind,
            MaidActionCompletion completion,
            MaidContractPredicate goal
    ) {
        return Map.entry(intentType, new MaidActionContract(
                kind,
                completion,
                COMMON_PRECONDITIONS,
                List.of(goal),
                COMMON_INVARIANTS
        ));
    }

    private static boolean isImmediateDirective(JobType type) {
        return type == JobType.FOLLOW || type == JobType.STAY || type == JobType.CANCEL;
    }

    private static boolean isReadOnly(MaidControlIntent intent) {
        return intent instanceof MaidControlIntent.QueryStatus
                || intent instanceof MaidControlIntent.QueryInventory;
    }

    private static Component status(AiPartnerEntity partner) {
        String contractStatus = partner.getCurrentContract()
                .map(contract -> contract.status().name())
                .orElse("NONE");
        String jobType = partner.getCurrentContract()
                .map(contract -> contract.job().type().name())
                .orElse("NONE");
        return Component.translatable(
                "message.ai-partner.status",
                partner.getMode().name(),
                jobType,
                contractStatus,
                partner.getWorkMode().serializedName(),
                partner.getCombatPolicy().serializedName(),
                partner.getGrowthLevel(),
                partner.getGrowthExperience(),
                partner.getAffection()
        );
    }

    private static String statusEvidence(AiPartnerEntity partner) {
        return "mode=" + partner.getMode().name()
                + ",task=" + partner.getCurrentContract().map(contract -> contract.job().type().name()).orElse("NONE")
                + ",contract=" + partner.getCurrentContract().map(contract -> contract.status().name()).orElse("NONE")
                + ",work=" + partner.getWorkMode().serializedName()
                + ",combat=" + partner.getCombatPolicy().serializedName();
    }

    private static String responseKey(JobType type) {
        return switch (type) {
            case FOLLOW -> "message.ai-partner.following";
            case STAY -> "message.ai-partner.staying";
            case CANCEL -> "message.ai-partner.cancelled";
            case COLLECT_BLOCK -> "message.ai-partner.collecting";
            case DEPOSIT_ITEM -> "message.ai-partner.depositing";
            case COLLECT_AND_DEPOSIT -> "message.ai-partner.collecting_and_depositing";
            case TRANSFER_ITEM -> "message.ai-partner.transferring";
        };
    }
}
