package io.github.ozozorz.aipartner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证主人索引、当前选择和 SavedData codec 的往返一致性。
 */
class MaidOwnershipStateTest {
    @Test
    void registersSelectsUnregistersAndRoundTrips() {
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MaidOwnershipState state = new MaidOwnershipState();
        state.register(owner, first);
        state.register(owner, second);
        assertTrue(state.select(owner, second));

        JsonElement encoded = MaidOwnershipState.CODEC
                .encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow();
        MaidOwnershipState decoded = MaidOwnershipState.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();

        assertEquals(2, decoded.ownedCount(owner));
        assertEquals(second, decoded.selected(owner).orElseThrow());
        decoded.unregister(owner, second);
        assertEquals(first, decoded.selected(owner).orElseThrow());
    }
}
