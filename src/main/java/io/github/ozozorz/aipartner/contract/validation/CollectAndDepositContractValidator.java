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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 一次性验证固定“采集后存放”序列的全部初始条件。
 */
public final class CollectAndDepositContractValidator implements TaskContractValidator {
    @Override
    public ContractDecision validate(AiPartnerEntity partner, ServerPlayer player, JobSpec candidate) {
        Block block = AllowedTargets.resolveCollectibleBlock(candidate.target()).orElse(null);
        Item item = block == null ? null : AllowedTargets.asCollectibleItem(block).orElse(null);
        if (block == null || item == null) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
        }
        if (!partner.hasAxe()) {
            return ContractDecision.rejected(FailureCode.MISSING_TOOL, "message.ai-partner.missing_axe");
        }
        if (!partner.canStore(item, candidate.quantity())) {
            return ContractDecision.rejected(FailureCode.INVENTORY_FULL, "message.ai-partner.inventory_full");
        }
        ServerLevel level = player.level();
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return ContractDecision.rejected(FailureCode.PERMISSION_DENIED, "message.ai-partner.mob_griefing_disabled");
        }
        if (!AllowedTargets.existsNear(partner, block, candidate.radius())) {
            return ContractDecision.rejected(FailureCode.TARGET_NOT_FOUND, "message.ai-partner.target_not_found");
        }
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
                candidate,
                List.of(
                        "owner_is_online",
                        "target_is_allowed",
                        "target_exists_in_radius",
                        "axe_is_available",
                        "inventory_has_capacity(" + candidate.quantity() + ")",
                        "accessible_chest_has_capacity"
                ),
                List.of(
                        "maid_inventory_delta_during_collect(" + candidate.target() + ") >= " + candidate.quantity(),
                        "container_item_delta(" + candidate.target() + ") >= " + candidate.quantity()
                ),
                List.of(
                        "phase_order == COLLECT_BLOCK_THEN_DEPOSIT_ITEM",
                        "distance_from_origin <= " + candidate.radius(),
                        "only_break_target_block",
                        "only_move_target_item",
                        "do_not_modify_locked_container",
                        "do_not_continue_cancelled_actions"
                )
        );
    }
}
