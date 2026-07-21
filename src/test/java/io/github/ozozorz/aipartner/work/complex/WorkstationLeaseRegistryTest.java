package io.github.ozozorz.aipartner.work.complex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证同一熔炉只能被一名女仆持有，且存档中的租约标识可以重新取得。 */
class WorkstationLeaseRegistryTest {
    private static final BlockPos FURNACE = new BlockPos(4, 64, -2);

    @AfterEach
    void clearLeases() {
        WorkstationLeaseRegistry.clear();
    }

    @Test
    void excludesAnotherMaidUntilOwnerReleasesLease() {
        UUID firstMaid = UUID.randomUUID();
        UUID secondMaid = UUID.randomUUID();
        UUID lease = WorkstationLeaseRegistry.acquire(Level.OVERWORLD, FURNACE, firstMaid, null).orElseThrow();

        assertFalse(WorkstationLeaseRegistry.isAvailable(Level.OVERWORLD, FURNACE, secondMaid));
        assertTrue(WorkstationLeaseRegistry.acquire(Level.OVERWORLD, FURNACE, secondMaid, null).isEmpty());

        WorkstationLeaseRegistry.release(Level.OVERWORLD, FURNACE, firstMaid, lease);
        assertTrue(WorkstationLeaseRegistry.isAvailable(Level.OVERWORLD, FURNACE, secondMaid));
    }

    @Test
    void reacquiresPersistedLeaseForSameMaid() {
        UUID maid = UUID.randomUUID();
        UUID persisted = UUID.randomUUID();

        UUID acquired = WorkstationLeaseRegistry.acquire(
                Level.OVERWORLD,
                FURNACE,
                maid,
                persisted
        ).orElseThrow();
        UUID repeated = WorkstationLeaseRegistry.acquire(
                Level.OVERWORLD,
                FURNACE,
                maid,
                persisted
        ).orElseThrow();

        assertEquals(persisted, acquired);
        assertEquals(acquired, repeated);
        assertTrue(WorkstationLeaseRegistry.isHeldBy(Level.OVERWORLD, FURNACE, maid, persisted));
    }
}
