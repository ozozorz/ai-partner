package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 将候选 JobSpec 与服务器内置规则合并，并执行白名单、权限和前置条件验证。
 */
public final class ContractCompiler {
    private ContractCompiler() {
    }

    /**
     * 先验证后生成正式契约；任何拒绝结果都不会进入实体执行器。
     */
    public static ContractDecision compile(AiPartnerEntity partner, ServerPlayer player, JobSpec candidate) {
        if (!partner.isOwnedBy(player)) {
            return ContractDecision.rejected(FailureCode.PERMISSION_DENIED, "message.ai-partner.not_owner");
        }
        if (partner.level() != player.level()) {
            return ContractDecision.rejected(FailureCode.DIFFERENT_DIMENSION, "message.ai-partner.different_dimension");
        }
        TaskDefinition definition = TaskDefinitionRegistry.get(candidate.type());
        if (!definition.implemented()) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.unsupported_milestone");
        }
        if (!definition.acceptsShape(candidate)) {
            if (definition.targetRequired() && !definition.allowedTargets().contains(candidate.target())) {
                return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
            }
            return ContractDecision.rejected(FailureCode.INVALID_PARAMETER, "message.ai-partner.invalid_parameter");
        }
        return switch (candidate.type()) {
            case FOLLOW -> accept(
                    candidate,
                    List.of("owner_is_online", "same_dimension"),
                    List.of("maintain_distance_to_owner"),
                    List.of("do_not_attack_friendly_entities", "do_not_modify_world")
            );
            case STAY -> accept(
                    candidate,
                    List.of("same_dimension"),
                    List.of("navigation_is_stopped"),
                    List.of("do_not_attack_friendly_entities", "do_not_modify_world")
            );
            case CANCEL -> accept(
                    candidate,
                    List.of("owner_is_online"),
                    List.of("no_active_work_task"),
                    List.of("do_not_continue_cancelled_actions")
            );
            case COLLECT_BLOCK -> compileCollectBlock(partner, candidate);
            case DEPOSIT_ITEM -> compileDepositItem(partner, player, candidate);
            case COLLECT_AND_DEPOSIT -> compileCollectAndDeposit(partner, player, candidate);
        };
    }

    private static ContractDecision compileCollectBlock(AiPartnerEntity partner, JobSpec candidate) {
        Block block = AllowedTargets.resolveCollectibleBlock(candidate.target()).orElse(null);
        Item item = block == null ? null : AllowedTargets.asCollectibleItem(block).orElse(null);
        if (block == null || item == null) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
        }
        if (!partner.hasAxe()) {
            return ContractDecision.rejected(FailureCode.MISSING_TOOL, "message.ai-partner.missing_axe");
        }
        if (!partner.canStore(item)) {
            return ContractDecision.rejected(FailureCode.INVENTORY_FULL, "message.ai-partner.inventory_full");
        }
        if (!(partner.level() instanceof ServerLevel serverLevel)
                || !serverLevel.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return ContractDecision.rejected(FailureCode.PERMISSION_DENIED, "message.ai-partner.mob_griefing_disabled");
        }
        if (!AllowedTargets.existsNear(partner, block, candidate.radius())) {
            return ContractDecision.rejected(FailureCode.TARGET_NOT_FOUND, "message.ai-partner.target_not_found");
        }
        return accept(
                candidate,
                List.of("owner_is_online", "target_is_allowed", "target_exists_in_radius", "axe_is_available", "inventory_has_space"),
                List.of("maid_inventory_delta(" + candidate.target() + ") >= " + candidate.quantity()),
                List.of(
                        "distance_from_origin <= " + candidate.radius(),
                        "mob_griefing_is_enabled",
                        "do_not_attack_friendly_entities",
                        "only_break_target_block"
                )
        );
    }

    private static ContractDecision compileDepositItem(
            AiPartnerEntity partner,
            ServerPlayer player,
            JobSpec candidate
    ) {
        Item item = AllowedTargets.resolveDepositableItem(candidate.target()).orElse(null);
        if (item == null) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
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
                new net.minecraft.world.item.ItemStack(item),
                candidate.quantity()
        ).isEmpty()) {
            return ContractDecision.rejected(FailureCode.CONTAINER_FULL, "message.ai-partner.container_full");
        }
        return accept(
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

    /**
     * 一次性验证固定组合任务的采集与存箱前置条件，避免第一阶段完成后才发现初始箱子不可用。
     */
    private static ContractDecision compileCollectAndDeposit(
            AiPartnerEntity partner,
            ServerPlayer player,
            JobSpec candidate
    ) {
        Block block = AllowedTargets.resolveCollectibleBlock(candidate.target()).orElse(null);
        Item item = block == null ? null : AllowedTargets.asCollectibleItem(block).orElse(null);
        if (block == null || item == null) {
            return ContractDecision.rejected(FailureCode.UNSUPPORTED_JOB, "message.ai-partner.target_not_allowed");
        }
        if (!partner.hasAxe()) {
            return ContractDecision.rejected(FailureCode.MISSING_TOOL, "message.ai-partner.missing_axe");
        }
        if (!partner.canStore(item)) {
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
                new net.minecraft.world.item.ItemStack(item),
                candidate.quantity()
        ).isEmpty()) {
            return ContractDecision.rejected(FailureCode.CONTAINER_FULL, "message.ai-partner.container_full");
        }
        return accept(
                candidate,
                List.of(
                        "owner_is_online",
                        "target_is_allowed",
                        "target_exists_in_radius",
                        "axe_is_available",
                        "inventory_has_space",
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

    private static ContractDecision accept(
            JobSpec candidate,
            List<String> preconditions,
            List<String> goals,
            List<String> invariants
    ) {
        TaskContract contract = TaskContract.accepted(
                candidate,
                preconditions,
                goals,
                invariants,
                TaskContract.FailurePolicy.DEFAULT
        );
        return ContractDecision.accepted(contract);
    }
}
