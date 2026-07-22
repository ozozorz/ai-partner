package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerPlayer;

/** Compatibility facade for callers that submit one direct semantic action. */
public final class MaidControlService {
    private MaidControlService() {
    }

    /** Routes a direct action through the canonical action registry. */
    public static MaidControlDecision apply(
            AiPartnerEntity partner,
            ServerPlayer player,
            MaidControlIntent intent,
            String rawInstruction,
            String sourceId
    ) {
        return MaidActionRegistry.execute(
                partner,
                player,
                intent,
                MaidActionRequest.direct(rawInstruction, sourceId)
        );
    }
}
