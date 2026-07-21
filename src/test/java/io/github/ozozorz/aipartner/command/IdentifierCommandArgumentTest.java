package io.github.ozozorz.aipartner.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Verifies that formal maid commands accept normal namespaced Minecraft identifiers. */
class IdentifierCommandArgumentTest {
    @Test
    void parsesNamespacedTargetBeforeQuantityAndRadius() throws Exception {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        AtomicReference<Identifier> target = new AtomicReference<>();

        dispatcher.register(LiteralArgumentBuilder.<Object>literal("maid")
                .then(LiteralArgumentBuilder.<Object>literal("collect")
                        .then(RequiredArgumentBuilder.<Object, Identifier>argument("block", IdentifierArgument.id())
                                .then(RequiredArgumentBuilder.<Object, Integer>argument(
                                                "quantity",
                                                IntegerArgumentType.integer(1, 64)
                                        )
                                        .then(RequiredArgumentBuilder.<Object, Integer>argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, 24)
                                                )
                                                .executes(context -> {
                                                    target.set(context.getArgument("block", Identifier.class));
                                                    return 1;
                                                })))))
        );

        int result = dispatcher.execute("maid collect minecraft:oak_log 1 8", new Object());

        assertEquals(1, result);
        assertEquals("minecraft:oak_log", target.get().toString());
    }

    @Test
    void transferAcceptsAnyNamespacedItemIdentifier() throws Exception {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        AtomicReference<Identifier> target = new AtomicReference<>();

        dispatcher.register(LiteralArgumentBuilder.<Object>literal("maid")
                .then(LiteralArgumentBuilder.<Object>literal("transfer")
                        .then(RequiredArgumentBuilder.<Object, Identifier>argument("item", IdentifierArgument.id())
                                .then(RequiredArgumentBuilder.<Object, Integer>argument(
                                                "quantity",
                                                IntegerArgumentType.integer(1, 64)
                                        )
                                        .executes(context -> {
                                            target.set(context.getArgument("item", Identifier.class));
                                            return 1;
                                        }))))
        );

        int result = dispatcher.execute("maid transfer minecraft:iron_ingot 16", new Object());

        assertEquals(1, result);
        assertEquals("minecraft:iron_ingot", target.get().toString());
    }
}
