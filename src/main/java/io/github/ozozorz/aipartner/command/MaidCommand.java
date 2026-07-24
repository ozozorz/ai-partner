package io.github.ozozorz.aipartner.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 注册与三种模式和技能化工作系统一致的 `/maid` 命令。
 */
public final class MaidCommand {
    private MaidCommand() {
    }

    /**
     * 注册生成、选择、模式、工作技能组合和生活配置命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("maid")
                        .executes(MaidCommand::showHelp)
                        .then(Commands.literal("help").executes(MaidCommand::showHelp))
                        .then(Commands.literal("spawn").executes(MaidCommand::spawn))
                        .then(Commands.literal("list").executes(MaidCommand::listMaids))
                        .then(Commands.literal("select")
                                .then(Commands.argument("maid", StringArgumentType.string())
                                        .executes(MaidCommand::selectMaid)))
                        .then(Commands.literal("status").executes(MaidCommand::status))
                        .then(Commands.literal("inventory").executes(MaidCommand::inventory))
                        .then(Commands.literal("retrieve").executes(MaidCommand::retrieveInventory))
                        .then(Commands.literal("skills").executes(MaidCommand::skills))
                        .then(Commands.literal("memory").executes(MaidCommand::memory))
                        .then(Commands.literal("mode")
                                .executes(MaidCommand::showMode)
                                .then(modeBranch("follow", PartnerMode.FOLLOW))
                                .then(modeBranch("stay", PartnerMode.STAY))
                                .then(modeBranch("work", PartnerMode.WORK)))
                        .then(Commands.literal("follow").executes(context -> setMode(context, PartnerMode.FOLLOW)))
                        .then(Commands.literal("stay").executes(context -> setMode(context, PartnerMode.STAY)))
                        .then(Commands.literal("work")
                                .executes(MaidCommand::showWorkMode)
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(MaidWorkMode.values())
                                                        .map(MaidWorkMode::serializedName),
                                                builder
                                        ))
                                        .executes(MaidCommand::setWorkMode)))
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
        ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeBranch(String literal, PartnerMode mode) {
        return Commands.literal(literal).executes(context -> setMode(context, mode));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> locationTypeBranch(
            String literal,
            ActivityLocationType type,
            boolean set
    ) {
        return Commands.literal(literal).executes(context -> configureLocation(context, type, set));
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.help"), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (PartnerService.spawnPartner(player).isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.spawn_failed"));
            return 0;
        }
        return success(context, Component.translatable("message.ai-partner.spawned"));
    }

    private static int listMaids(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity selected = PartnerService.findOwnedPartner(player).orElse(null);
        java.util.List<AiPartnerEntity> maids = PartnerService.findOwnedPartners(player);
        if (maids.isEmpty()) {
            return failure(context, "message.ai-partner.not_found");
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
        return success(context, Component.translatable("message.ai-partner.selected", selected.getName()));
    }

    private static int showMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        return partner == null ? 0 : success(
                context,
                Component.translatable("message.ai-partner.mode", partner.getMode().name())
        );
    }

    private static int setMode(
            CommandContext<CommandSourceStack> context,
            PartnerMode mode
    ) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        partner.setMode(mode);
        return success(context, Component.translatable(
                "message.ai-partner.mode_changed",
                mode.name()
        ));
    }

    private static int returnHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        partner.requestReturnHome(player);
        return success(context, Component.translatable("message.ai-partner.returning_home"));
    }

    private static int renameMaid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        String name = StringArgumentType.getString(context, "name").strip();
        if (name.isEmpty() || name.length() > 32) {
            return failure(context, "message.ai-partner.invalid_name");
        }
        partner.setCustomName(Component.literal(name));
        partner.setCustomNameVisible(true);
        return success(context, Component.translatable("message.ai-partner.renamed", name));
    }

    private static int setSchedule(
            CommandContext<CommandSourceStack> context,
            ScheduleType scheduleType
    ) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        partner.setScheduleType(scheduleType);
        return success(context, Component.translatable(
                "message.ai-partner.schedule_changed",
                scheduleType.name()
        ));
    }

    private static int configureLocation(
            CommandContext<CommandSourceStack> context,
            ActivityLocationType type,
            boolean set
    ) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        if (set) {
            partner.setActivityLocation(type);
        } else {
            partner.clearActivityLocation(type);
        }
        return success(context, Component.translatable(
                "message.ai-partner.location_changed",
                type.name()
        ));
    }

    private static int setHomeBound(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        partner.setHomeBound(enabled);
        return success(context, Component.translatable("message.ai-partner.home_bound_changed", enabled));
    }

    private static int setActivityRadius(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(context, "radius");
        partner.setActivityRadius(radius);
        return success(context, Component.translatable("message.ai-partner.radius_changed", radius));
    }

    private static int showWorkMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        return partner == null ? 0 : success(context, Component.translatable(
                "message.ai-partner.work_mode_current",
                partner.getWorkMode().serializedName(),
                partner.getWorkExecutionState()
        ));
    }

    private static int setWorkMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        String requested = StringArgumentType.getString(context, "profile");
        MaidWorkMode mode = MaidWorkMode.parse(requested).orElse(null);
        if (mode == null) {
            context.getSource().sendFailure(Component.translatable(
                    "message.ai-partner.invalid_work_mode",
                    requested
            ));
            return 0;
        }
        partner.setWorkMode(mode);
        return success(context, Component.translatable(
                "message.ai-partner.work_mode_changed",
                mode.serializedName()
        ));
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        return partner == null ? 0 : success(context, Component.translatable(
                "message.ai-partner.status",
                partner.getName(),
                partner.getMode().name(),
                partner.getWorkMode().serializedName(),
                partner.getScheduleActivity().name(),
                Math.round(partner.getHealth()),
                Math.round(partner.getMaxHealth())
        ));
    }

    private static int inventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        return partner == null ? 0 : success(
                context,
                Component.translatable("message.ai-partner.inventory", partner.inventorySummary())
        );
    }

    private static int retrieveInventory(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        int returned = partner.returnInventoryTo(player);
        return success(context, Component.translatable("message.ai-partner.retrieved", returned));
    }

    private static int skills(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        String names = partner.getSkills().availableSkills().stream()
                .map(skill -> skill.serializedName())
                .collect(java.util.stream.Collectors.joining(", "));
        return success(context, Component.translatable(
                "message.ai-partner.skills",
                partner.getSkills().availableSkills().size(),
                names
        ));
    }

    private static int memory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AiPartnerEntity partner = requirePartner(context);
        if (partner == null) {
            return 0;
        }
        java.util.List<String> memories = partner.getSkills().containerMemory().recentSummaries(8);
        if (memories.isEmpty()) {
            return success(context, Component.translatable("message.ai-partner.memory_empty"));
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.ai-partner.memory_count",
                partner.getSkills().containerMemory().rememberedContainerCount()
        ), false);
        memories.forEach(summary -> context.getSource().sendSuccess(() -> Component.literal(" - " + summary), false));
        return memories.size();
    }

    private static @org.jspecify.annotations.Nullable AiPartnerEntity requirePartner(
            CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
        }
        return partner;
    }

    private static int success(CommandContext<CommandSourceStack> context, Component message) {
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    private static int failure(CommandContext<CommandSourceStack> context, String translationKey) {
        context.getSource().sendFailure(Component.translatable(translationKey));
        return 0;
    }
}
