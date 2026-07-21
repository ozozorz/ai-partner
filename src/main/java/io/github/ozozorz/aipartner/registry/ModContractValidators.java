package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.contract.ContractAcceptance;
import io.github.ozozorz.aipartner.contract.TaskContractValidatorRegistry;
import io.github.ozozorz.aipartner.contract.validation.CollectAndDepositContractValidator;
import io.github.ozozorz.aipartner.contract.validation.CollectBlockContractValidator;
import io.github.ozozorz.aipartner.contract.validation.DepositItemContractValidator;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;

/**
 * 当前版本全部契约验证器的组合根。
 */
public final class ModContractValidators {
    private static final TaskContractValidatorRegistry REGISTRY = createRegistry();

    private ModContractValidators() {
    }

    public static TaskContractValidatorRegistry registry() {
        return REGISTRY;
    }

    private static TaskContractValidatorRegistry createRegistry() {
        return new TaskContractValidatorRegistry()
                .register(JobType.FOLLOW, (partner, player, candidate) -> ContractAcceptance.accept(
                        candidate,
                        List.of("owner_is_online", "same_dimension"),
                        List.of("maintain_distance_to_owner"),
                        List.of("do_not_attack_friendly_entities", "do_not_modify_world")
                ))
                .register(JobType.STAY, (partner, player, candidate) -> ContractAcceptance.accept(
                        candidate,
                        List.of("same_dimension"),
                        List.of("navigation_is_stopped"),
                        List.of("do_not_attack_friendly_entities", "do_not_modify_world")
                ))
                .register(JobType.CANCEL, (partner, player, candidate) -> ContractAcceptance.accept(
                        candidate,
                        List.of("owner_is_online"),
                        List.of("no_active_work_task"),
                        List.of("do_not_continue_cancelled_actions")
                ))
                .register(JobType.COLLECT_BLOCK, new CollectBlockContractValidator())
                .register(JobType.DEPOSIT_ITEM, new DepositItemContractValidator())
                .register(JobType.COLLECT_AND_DEPOSIT, new CollectAndDepositContractValidator())
                .freeze();
    }
}
