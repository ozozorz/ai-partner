package io.github.ozozorz.aipartner.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 通过真实 ValueOutput/ValueInput 往返验证完整契约谓词和失败策略的存档格式。
 */
class MaidTaskRuntimePersistenceTest {
    private static HolderLookup.Provider registryLookup;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registryLookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void roundTripsCompleteRunningContract() {
        TaskContract contract = TaskContract.accepted(
                new JobSpec(JobType.COLLECT_BLOCK, "minecraft:oak_log", 8, 16),
                List.of("owner_is_online", "inventory_has_capacity(8)"),
                List.of("maid_inventory_delta(minecraft:oak_log) >= 8"),
                List.of("only_break_target_block", "distance_from_origin <= 16"),
                new TaskContract.FailurePolicy(3, 0, 120),
                TaskContract.ExecutionAnchor.bound(
                        java.util.UUID.randomUUID(),
                        "minecraft:overworld",
                        net.minecraft.core.BlockPos.asLong(12, 64, -4)
                )
        );
        contract.markRunning();
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);

        MaidTaskRuntime.saveContract(output, contract);
        TaskContract restored = MaidTaskRuntime.restoreContract(input(output), contract.contractId().toString());

        assertEquals(contract.contractId(), restored.contractId());
        assertEquals(contract.job(), restored.job());
        assertEquals(contract.preconditions(), restored.preconditions());
        assertEquals(contract.goalPredicates(), restored.goalPredicates());
        assertEquals(contract.invariants(), restored.invariants());
        assertEquals(contract.failurePolicy(), restored.failurePolicy());
        assertEquals(contract.executionAnchor(), restored.executionAnchor());
        assertEquals(ContractStatus.RUNNING, restored.status());
        assertEquals(FailureCode.NONE, restored.failureCode());
    }

    @Test
    void rejectsUnknownPredicatePersistenceVersion() {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        output.putString("ContractJobType", JobType.FOLLOW.name());
        output.putString("ContractStatus", ContractStatus.RUNNING.name());
        output.putString("ContractFailureCode", FailureCode.NONE.name());
        output.putInt("ContractPredicateFormatVersion", 99);

        assertThrows(IllegalArgumentException.class, () -> MaidTaskRuntime.restoreContract(
                input(output),
                java.util.UUID.randomUUID().toString()
        ));
    }

    private static ValueInput input(TagValueOutput output) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registryLookup, output.buildResult());
    }
}
