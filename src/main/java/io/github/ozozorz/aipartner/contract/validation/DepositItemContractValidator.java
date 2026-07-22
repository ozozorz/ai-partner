package io.github.ozozorz.aipartner.contract.validation;

import io.github.ozozorz.aipartner.contract.ContractAcceptance;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContractValidator;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 验证单箱存放任务的物品数量、箱子可访问性和容量。
 */
public final class DepositItemContractValidator implements TaskContractValidator {
    @Override
    public ContractDecision validate(AiPartnerEntity partner, ServerPlayer player, JobSpec candidate) {
        Item item = AllowedTargets.resolveDepositableItem(candidate.target()).orElse(null);
        if (item == null) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.item_not_registered");
        }
        if (partner.countItem(item) < candidate.quantity()) {
            return ContractDecision.rejected(FailureCode.MISSING_ITEM, "message.ai-partner.missing_item");
        }
        ServerLevel level = player.level();
        if (ContainerTargets.findNearestChest(level, partner.blockPosition(), candidate.radius(), player).isEmpty()) {
            return ContractDecision.rejected(FailureCode.TARGET_NOT_FOUND, "message.ai-partner.chest_not_found");
        }
        if (ContainerTargets.findNearestChestWithCapacity(
                level,
                partner.blockPosition(),
                candidate.radius(),
                player,
                new ItemStack(item),
                candidate.quantity()
        ).isEmpty()) {
            return ContractDecision.rejected(FailureCode.CONTAINER_FULL, "message.ai-partner.container_full");
        }
        return ContractAcceptance.accept(
                partner,
                player,
                candidate,
                List.of("owner_is_online", "item_is_allowed", "maid_has_requested_quantity", "accessible_chest_exists"),
                List.of("container_item_delta(" + candidate.target() + ") >= " + candidate.quantity()),
                List.of(
                        "distance_from_origin <= " + candidate.radius(),
                        "only_move_target_item",
                        "do_not_modify_locked_container",
                        "do_not_continue_cancelled_actions"
                )
        );
    }
}
