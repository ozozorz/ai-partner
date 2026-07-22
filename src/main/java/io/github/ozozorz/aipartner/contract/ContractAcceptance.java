package io.github.ozozorz.aipartner.contract;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * 以统一失败策略创建已接受契约。
 */
public final class ContractAcceptance {
    private ContractAcceptance() {
    }

    public static ContractDecision accept(
            AiPartnerEntity partner,
            ServerPlayer player,
            JobSpec candidate,
            List<String> preconditions,
            List<String> goals,
            List<String> invariants
    ) {
        return ContractDecision.accepted(TaskContract.accepted(
                candidate,
                preconditions,
                goals,
                invariants,
                TaskContract.FailurePolicy.DEFAULT,
                TaskContract.ExecutionAnchor.bound(
                        player.getUUID(),
                        partner.level().dimension().identifier().toString(),
                        partner.blockPosition().asLong()
                )
        ));
    }
}
