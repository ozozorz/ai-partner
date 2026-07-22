package io.github.ozozorz.aipartner.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.ozozorz.aipartner.conversation.MaidConversationService;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.control.MaidControlDecision;
import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.control.MaidControlService;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * 注册 `/maid` 交互入口；所有动作先经过契约编译器，再交给实体执行。
 */
public final class MaidCommand {
    private MaidCommand() {
    }

    /**
     * 注册生成、状态、基础任务和自然语言消息子命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("maid")
                        .executes(MaidCommand::showHelp)
                        .then(Commands.literal("spawn").executes(MaidCommand::spawn))
                        .then(Commands.literal("status").executes(MaidCommand::status))
                        .then(Commands.literal("inventory").executes(MaidCommand::inventory))
                        .then(Commands.literal("retrieve").executes(MaidCommand::retrieveInventory))
                        .then(Commands.literal("list").executes(MaidCommand::listMaids))
                        .then(Commands.literal("select")
                                .then(Commands.argument("maid", StringArgumentType.string())
                                        .executes(MaidCommand::selectMaid)))
                        .then(Commands.literal("home").executes(MaidCommand::returnHome))
                        .then(Commands.literal("name")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(MaidCommand::renameMaid)))
                        .then(Commands.literal("schedule")
                                .then(Commands.literal("day")
                                        .executes(context -> setSchedule(context, ScheduleType.DAY_SHIFT)))
                                .then(Commands.literal("night")
                                        .executes(context -> setSchedule(context, ScheduleType.NIGHT_SHIFT)))
                                .then(Commands.literal("all-day")
                                        .executes(context -> setSchedule(context, ScheduleType.ALL_DAY))))
                        .then(Commands.literal("location")
                                .then(Commands.literal("set")
                                        .then(locationTypeBranch("work", ActivityLocationType.WORK, true))
                                        .then(locationTypeBranch("leisure", ActivityLocationType.LEISURE, true))
                                        .then(locationTypeBranch("sleep", ActivityLocationType.SLEEP, true)))
                                .then(Commands.literal("clear")
                                        .then(locationTypeBranch("work", ActivityLocationType.WORK, false))
                                        .then(locationTypeBranch("leisure", ActivityLocationType.LEISURE, false))
                                        .then(locationTypeBranch("sleep", ActivityLocationType.SLEEP, false))))
                        .then(Commands.literal("home-bound")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(MaidCommand::setHomeBound)))
                        .then(Commands.literal("radius")
                                .then(Commands.argument(
                                        "radius",
                                        IntegerArgumentType.integer(
                                                1,
                                                MaidGameplayConfig.get().maximumActivityRadius()
                                        )
                                ).executes(MaidCommand::setActivityRadius)))
                        .then(Commands.literal("work")
                                .executes(MaidCommand::showWorkMode)
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(MaidWorkMode.values())
                                                        .map(MaidWorkMode::serializedName),
                                                builder
                                        ))
                                        .executes(MaidCommand::setWorkMode)))
                        .then(Commands.literal("combat")
                                .executes(MaidCommand::showCombatPolicy)
                                .then(Commands.argument("policy", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(CombatPolicy.values())
                                                        .map(CombatPolicy::serializedName),
                                                builder
                                        ))
                                        .executes(MaidCommand::setCombatPolicy)))
                        .then(createDriverCommand())
                        .then(Commands.literal("follow").executes(context -> runBasicJob(context, JobType.FOLLOW)))
                        .then(Commands.literal("stay").executes(context -> runBasicJob(context, JobType.STAY)))
                        .then(Commands.literal("cancel").executes(context -> runBasicJob(context, JobType.CANCEL)))
                        .then(Commands.literal("collect")
                                .then(Commands.argument("block", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedBlockIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runCollectJob(
                                                        context,
                                                        AllowedTargets.DEFAULT_COLLECT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, AllowedTargets.MAX_COLLECT_RADIUS)
                                                ).executes(context -> runCollectJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.literal("deposit")
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedBlockIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runDepositJob(
                                                        context,
                                                        ContainerTargets.DEFAULT_DEPOSIT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, ContainerTargets.MAX_DEPOSIT_RADIUS)
                                                ).executes(context -> runDepositJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.literal("transfer")
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedItemIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runTransferJob(
                                                        context,
                                                        ContainerTargets.DEFAULT_DEPOSIT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, ContainerTargets.MAX_DEPOSIT_RADIUS)
                                                ).executes(context -> runTransferJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.literal("collect-and-deposit")
                                .then(Commands.argument("block", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedBlockIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runCollectAndDepositJob(
                                                        context,
                                                        AllowedTargets.DEFAULT_COLLECT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, AllowedTargets.MAX_COLLECT_RADIUS)
                                                ).executes(context -> runCollectAndDepositJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(MaidCommand::handleMessage))
        ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> locationTypeBranch(
            String literal,
            ActivityLocationType type,
            boolean set
    ) {
        return Commands.literal(literal).executes(context -> configureLocation(context, type, set));
    }

    /** Builds the per-maid LOCAL/LLM mode command; credentials remain server-configured. */
    private static LiteralArgumentBuilder<CommandSourceStack> createDriverCommand() {
        return Commands.literal("driver")
                .executes(MaidCommand::showDriverSettings)
                .then(Commands.literal("mode")
                        .then(Commands.argument("driver-mode", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(MaidDriveMode.values())
                                                .map(MaidDriveMode::serializedName),
                                        builder
                                ))
                                .executes(MaidCommand::setDriverMode)))
                .then(Commands.literal("clear-memory").executes(MaidCommand::clearConversationMemory));
    }

    private static int showDriverSettings(
            CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        String readinessError = io.github.ozozorz.aipartner.llm.MaidControlLlmGateway
                .getInstance()
                .readinessError(partner.getLlmApiKeyEnvironmentVariable());
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.ai-partner.driver.status",
                partner.getName(),
                partner.getDriveMode().serializedName(),
                partner.getLlmApiKeyEnvironmentVariable(),
                readinessError == null ? "READY" : readinessError
        ), false);
        return 1;
    }

    private static int setDriverMode(
            CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        String rawMode = StringArgumentType.getString(context, "driver-mode");
        MaidDriveMode mode = MaidDriveMode.parse(rawMode).orElse(null);
        if (mode == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.driver.invalid_mode", rawMode));
            return 0;
        }
        partner.setDriveMode(mode);
        MaidConversationService.cancelPending(player.getUUID());
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.ai-partner.driver.mode_set",
                partner.getName(),
                mode.serializedName()
        ), false);
        return 1;
    }

    /** Clears bounded dialogue history after cancelling responses that could append stale text. */
    private static int clearConversationMemory(
            CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        MaidConversationService.cancelPending(player.getUUID());
        partner.conversationMemory().clear();
        context.getSource().sendSuccess(
                () -> Component.translatable("message.ai-partner.driver.memory_cleared", partner.getName()),
                false
        );
        return 1;
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.help"), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int currentCount = PartnerService.indexedOwnedCount(player);
        int maximum = MaidGameplayConfig.get().maxMaidsPerOwner();
        if (currentCount >= maximum) {
            context.getSource().sendFailure(Component.translatable(
                    "message.ai-partner.owner_limit",
                    maximum
            ));
            return 0;
        }
        if (PartnerService.spawnPartner(player).isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.spawn_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.spawned"), false);
        return 1;
    }

    private static int listMaids(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity selected = PartnerService.findOwnedPartner(player).orElse(null);
        java.util.List<AiPartnerEntity> maids = PartnerService.findOwnedPartners(player);
        if (maids.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }
        for (AiPartnerEntity maid : maids) {
            boolean active = selected != null && selected.getUUID().equals(maid.getUUID());
            context.getSource().sendSuccess(() -> Component.translatable(
                    "message.ai-partner.list_entry",
                    active ? "*" : " ",
                    maid.getName(),
                    maid.getStringUUID(),
                    maid.level().dimension().identifier().toString()
            ), false);
        }
        return maids.size();
    }

    private static int selectMaid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String selector = StringArgumentType.getString(context, "maid");
        AiPartnerEntity selected = PartnerService.selectOwnedPartner(player, selector).orElse(null);
        if (selected == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.select_failed", selector));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("message.ai-partner.selected", selected.getName()),
                false
        );
        return 1;
    }

    private static int returnHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(context, partner, player, new MaidControlIntent.ReturnHome(), "home");
    }

    private static int renameMaid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        String name = StringArgumentType.getString(context, "name").strip();
        return executeControl(context, partner, player, new MaidControlIntent.Rename(name), "name " + name);
    }

    private static int setSchedule(
            CommandContext<CommandSourceStack> context,
            ScheduleType scheduleType
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.SetSchedule(scheduleType),
                "schedule " + scheduleType.name()
        );
    }

    private static int configureLocation(
            CommandContext<CommandSourceStack> context,
            ActivityLocationType type,
            boolean set
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.ConfigureLocation(type, !set),
                "location " + (set ? "set " : "clear ") + type.name()
        );
    }

    private static int setHomeBound(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.SetHomeBound(enabled),
                "home-bound " + enabled
        );
    }

    private static int setActivityRadius(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(context, "radius");
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.SetRadius(radius),
                "radius " + radius
        );
    }

    private static int showWorkMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.ai-partner.work_mode_current",
                partner.getWorkMode().serializedName(),
                partner.getWorkExecutionState()
        ), false);
        return 1;
    }

    private static int setWorkMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        String requested = StringArgumentType.getString(context, "mode");
        MaidWorkMode mode = MaidWorkMode.parse(requested).orElse(null);
        if (mode == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.invalid_work_mode", requested));
            return 0;
        }
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.SetWorkMode(mode),
                "work " + mode.serializedName()
        );
    }

    private static int showCombatPolicy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.ai-partner.combat_policy_current",
                partner.getCombatPolicy().serializedName()
        ), false);
        return 1;
    }

    private static int setCombatPolicy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        String requested = StringArgumentType.getString(context, "policy");
        CombatPolicy policy = CombatPolicy.parse(requested).orElse(null);
        if (policy == null) {
            context.getSource().sendFailure(Component.translatable(
                    "message.ai-partner.invalid_combat_policy",
                    requested
            ));
            return 0;
        }
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.SetCombatPolicy(policy),
                "combat " + policy.serializedName()
        );
    }

    private static AiPartnerEntity requirePartner(
            CommandContext<CommandSourceStack> context,
            ServerPlayer player
    ) {
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
        }
        return partner;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(context, partner, player, new MaidControlIntent.QueryStatus(), "status");
    }

    private static int inventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(context, partner, player, new MaidControlIntent.QueryInventory(), "inventory");
    }

    private static int retrieveInventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context, player);
        if (partner == null) {
            return 0;
        }
        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.RetrieveInventory(),
                "retrieve"
        );
    }

    private static int runBasicJob(CommandContext<CommandSourceStack> context, JobType type) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        return compileAndRun(context, JobSpec.basic(type), type.name().toLowerCase());
    }

    private static int runCollectJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String block = IdentifierArgument.getId(context, "block").toString();
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.COLLECT_BLOCK, block, quantity, radius),
                "collect " + block + " " + quantity + " " + radius
        );
    }

    private static int runDepositJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String item = IdentifierArgument.getId(context, "item").toString();
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.DEPOSIT_ITEM, item, quantity, radius),
                "deposit " + item + " " + quantity + " " + radius
        );
    }

    /** 通用物流使用独立 JobType，并复用安全存箱执行器。 */
    private static int runTransferJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String item = IdentifierArgument.getId(context, "item").toString();
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.TRANSFER_ITEM, item, quantity, radius),
                "transfer " + item + " " + quantity + " " + radius
        );
    }

    private static int runCollectAndDepositJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String block = IdentifierArgument.getId(context, "block").toString();
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.COLLECT_AND_DEPOSIT, block, quantity, radius),
                "collect-and-deposit " + block + " " + quantity + " " + radius
        );
    }

    private static int handleMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String message = StringArgumentType.getString(context, "message");
        ServerPlayer player = context.getSource().getPlayerOrException();
        return MaidConversationService.submit(player, null, message) ? 1 : 0;
    }

    private static int compileAndRun(
            CommandContext<CommandSourceStack> context,
            JobSpec candidate,
            String rawInstruction
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }

        return executeControl(
                context,
                partner,
                player,
                new MaidControlIntent.RunTask(candidate),
                rawInstruction
        );
    }

    /** Sends every command capability through the same semantic action entry as UI and LLM plans. */
    private static int executeControl(
            CommandContext<CommandSourceStack> context,
            AiPartnerEntity partner,
            ServerPlayer player,
            MaidControlIntent intent,
            String rawInstruction
    ) {
        MaidControlDecision decision = MaidControlService.apply(
                partner,
                player,
                intent,
                rawInstruction,
                "DIRECT_COMMAND"
        );
        if (!decision.accepted()) {
            context.getSource().sendFailure(decision.message());
            return 0;
        }
        context.getSource().sendSuccess(decision::message, false);
        return 1;
    }

    private static void cancelPendingRequest(UUID playerId) {
        MaidConversationService.cancelPending(playerId);
    }
}
