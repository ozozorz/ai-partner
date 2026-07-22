package io.github.ozozorz.aipartner.control;

/** Typed predicates used by semantic action contracts. */
public enum MaidContractPredicate {
    PARTNER_ALIVE,
    ACTOR_IS_OWNER,
    SAME_DIMENSION,
    PARAMETERS_WITHIN_BOUNDARY,
    TASK_CONTRACT_ACCEPTED,
    REQUESTED_STATE_OBSERVED,
    QUERY_RESULT_OBSERVED,
    INVENTORY_TRANSFER_OBSERVED,
    OWNER_AND_DIMENSION_PRESERVED
}
