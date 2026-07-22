package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.order.MaidOrderService;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 将已经类型化的自然语言意图路由到现有服务端控制器；该层不依赖 LLM 或客户端实现。
 */
public final class MaidControlService {
    private MaidControlService() {
    }

    /**
     * 重新验证主人和维度后应用一个意图，任何拒绝都不会改变女仆状态。
     */
    public static MaidControlDecision apply(
            AiPartnerEntity partner,
            ServerPlayer player,
            MaidControlIntent intent,
            String rawInstruction,
            String sourceId
    ) {
        if (!partner.isAlive() || !partner.isOwnedBy(player)) {
            return MaidControlDecision.rejected(Component.translatable("message.ai-partner.not_owner"));
        }
        if (partner.level() != player.level()) {
            return MaidControlDecision.rejected(Component.translatable("message.ai-partner.different_dimension"));
        }

        return switch (intent) {
            case MaidControlIntent.RunTask runTask -> runTask(
                    partner,
                    player,
                    runTask.job(),
                    rawInstruction,
                    sourceId
            );
            case MaidControlIntent.SetWorkMode setWorkMode -> {
                partner.setWorkMode(setWorkMode.mode());
                yield MaidControlDecision.accepted(Component.translatable(
                        "message.ai-partner.work_mode_set",
                        setWorkMode.mode().serializedName()
                ));
            }
            case MaidControlIntent.SetSchedule setSchedule -> {
                partner.setScheduleType(setSchedule.schedule());
                yield MaidControlDecision.accepted(Component.translatable(
                        "message.ai-partner.schedule_set",
                        setSchedule.schedule().name()
                ));
            }
            case MaidControlIntent.SetCombatPolicy setCombatPolicy -> {
                partner.setCombatPolicy(setCombatPolicy.policy());
                yield MaidControlDecision.accepted(Component.translatable(
                        "message.ai-partner.combat_policy_set",
                        setCombatPolicy.policy().serializedName()
                ));
            }
            case MaidControlIntent.ReturnHome ignored -> {
                partner.requestReturnHome(player);
                yield MaidControlDecision.accepted(Component.translatable("message.ai-partner.returning_home"));
            }
            case MaidControlIntent.ConfigureLocation configureLocation -> {
                if (configureLocation.clear()) {
                    partner.clearActivityLocation(configureLocation.location());
                } else {
                    partner.setActivityLocation(configureLocation.location());
                }
                yield MaidControlDecision.accepted(Component.translatable(
                        configureLocation.clear()
                                ? "message.ai-partner.location_cleared"
                                : "message.ai-partner.location_set",
                        configureLocation.location().name()
                ));
            }
            case MaidControlIntent.SetHomeBound setHomeBound -> {
                partner.setHomeBound(setHomeBound.enabled());
                yield MaidControlDecision.accepted(Component.translatable(
                        "message.ai-partner.home_bound_set",
                        setHomeBound.enabled()
                ));
            }
            case MaidControlIntent.SetRadius setRadius -> setRadius(partner, setRadius.radius());
            case MaidControlIntent.Rename rename -> rename(partner, rename.name());
            case MaidControlIntent.QueryStatus ignored -> MaidControlDecision.accepted(status(partner));
            case MaidControlIntent.QueryInventory ignored -> MaidControlDecision.accepted(Component.translatable(
                    "message.ai-partner.inventory",
                    partner.inventorySummary()
            ));
            case MaidControlIntent.RetrieveInventory ignored -> retrieveInventory(partner, player);
        };
    }

    private static MaidControlDecision runTask(
            AiPartnerEntity partner,
            ServerPlayer player,
            JobSpec job,
            String rawInstruction,
            String sourceId
    ) {
        ContractDecision decision = MaidOrderService.submit(
                partner,
                player,
                job,
                rawInstruction,
                TaskExecutionPolicy.standard(sourceId)
        );
        if (!decision.accepted()) {
            return MaidControlDecision.rejected(Component.translatable(decision.messageKey()));
        }
        return MaidControlDecision.accepted(Component.translatable(responseKey(job.type())));
    }

    private static MaidControlDecision setRadius(AiPartnerEntity partner, int radius) {
        int maximum = MaidGameplayConfig.get().maximumActivityRadius();
        if (radius < 1 || radius > maximum) {
            return MaidControlDecision.rejected(Component.translatable(
                    "message.ai-partner.control.invalid_radius",
                    maximum
            ));
        }
        partner.setActivityRadius(radius);
        return MaidControlDecision.accepted(Component.translatable("message.ai-partner.radius_set", radius));
    }

    private static MaidControlDecision rename(AiPartnerEntity partner, String name) {
        if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) {
            return MaidControlDecision.rejected(Component.translatable("message.ai-partner.invalid_name"));
        }
        partner.setCustomName(Component.literal(name));
        partner.setCustomNameVisible(true);
        return MaidControlDecision.accepted(Component.translatable("message.ai-partner.renamed", name));
    }

    private static MaidControlDecision retrieveInventory(AiPartnerEntity partner, ServerPlayer player) {
        int returned = partner.returnInventoryTo(player);
        if (returned < 0) {
            return MaidControlDecision.rejected(Component.translatable("message.ai-partner.inventory_busy"));
        }
        return MaidControlDecision.accepted(Component.translatable(
                "message.ai-partner.inventory_returned",
                returned
        ));
    }

    private static Component status(AiPartnerEntity partner) {
        String contractStatus = partner.getCurrentContract()
                .map(contract -> contract.status().name())
                .orElse("NONE");
        String jobType = partner.getCurrentContract()
                .map(contract -> contract.job().type().name())
                .orElse("NONE");
        return Component.translatable(
                "message.ai-partner.status",
                partner.getMode().name(),
                jobType,
                contractStatus,
                partner.getWorkMode().serializedName(),
                partner.getCombatPolicy().serializedName(),
                partner.getGrowthLevel(),
                partner.getGrowthExperience(),
                partner.getAffection()
        );
    }

    private static String responseKey(JobType type) {
        return switch (type) {
            case FOLLOW -> "message.ai-partner.following";
            case STAY -> "message.ai-partner.staying";
            case CANCEL -> "message.ai-partner.cancelled";
            case COLLECT_BLOCK -> "message.ai-partner.collecting";
            case DEPOSIT_ITEM -> "message.ai-partner.depositing";
            case COLLECT_AND_DEPOSIT -> "message.ai-partner.collecting_and_depositing";
            case TRANSFER_ITEM -> "message.ai-partner.transferring";
        };
    }
}
