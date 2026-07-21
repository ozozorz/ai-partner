package io.github.ozozorz.aipartner.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.registry.ModContractValidators;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证每个公开 JobType 都有唯一、冻结的专属验证器。
 */
class TaskContractValidatorRegistryTest {
    @Test
    void everyPublishedJobHasAValidator() {
        assertEquals(Set.of(JobType.values()), ModContractValidators.registry().registeredJobTypes());
    }

    @Test
    void frozenRegistryRejectsLateRegistration() {
        TaskContractValidatorRegistry registry = new TaskContractValidatorRegistry().freeze();

        assertThrows(
                IllegalStateException.class,
                () -> registry.register(JobType.FOLLOW, (partner, player, candidate) -> null)
        );
    }
}
