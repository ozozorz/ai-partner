package io.github.ozozorz.aipartner.contract.validation;

import io.github.ozozorz.aipartner.contract.ContractAcceptance;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContractValidator;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 验证原木采集的目标、工具、空间、世界规则和附近目标。
 */
public final class CollectBlockContractValidator implements TaskContractValidator {
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
        return ContractAcceptance.accept(
                candidate,
                List.of(
                        "owner_is_online",
                        "target_is_allowed",
                        "target_exists_in_radius",
                        "axe_is_available",
                        "inventory_has_space"
                ),
                List.of("maid_inventory_delta(" + candidate.target() + ") >= " + candidate.quantity()),
                List.of(
                        "distance_from_origin <= " + candidate.radius(),
                        "mob_griefing_is_enabled",
                        "do_not_attack_friendly_entities",
                        "only_break_target_block"
                )
        );
    }
}
