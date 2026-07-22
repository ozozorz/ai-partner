package io.github.ozozorz.aipartner.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies the explicit privacy and context boundary of persisted dialogue history. */
class MaidConversationMemoryTest {
    private static HolderLookup.Provider registryLookup;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registryLookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void retainsOnlyTheNewestTwelveTurns() {
        MaidConversationMemory memory = new MaidConversationMemory();
        for (int index = 0; index < 14; index++) {
            memory.appendUser("message-" + index);
        }

        assertEquals(12, memory.snapshot().size());
        assertEquals("message-2", memory.snapshot().getFirst().content());
        assertEquals("message-13", memory.snapshot().getLast().content());
    }

    @Test
    void boundsIndividualHistoryEntries() {
        MaidConversationMemory memory = new MaidConversationMemory();

        memory.appendAssistant("x".repeat(600));

        assertEquals(512, memory.snapshot().getFirst().content().length());
    }

    @Test
    void roundTripsOnlyBoundedRoleAndContentFields() {
        MaidConversationMemory original = new MaidConversationMemory();
        original.appendUser("先收集八块橡木");
        original.appendAssistant("好，我会按顺序完成。");
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        original.save(output);

        MaidConversationMemory restored = new MaidConversationMemory();
        restored.load(TagValueInput.create(
                ProblemReporter.DISCARDING,
                registryLookup,
                output.buildResult()
        ));

        assertEquals(original.snapshot(), restored.snapshot());
    }
}
