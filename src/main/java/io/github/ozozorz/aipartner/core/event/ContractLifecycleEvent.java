package io.github.ozozorz.aipartner.core.event;

import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 契约接受、运行、恢复、完成、失败和取消事件。
 */
public record ContractLifecycleEvent(
        String event,
        String sourceId,
        AiPartnerEntity partner,
        @Nullable ServerPlayer actor,
        TaskContract contract,
        String detail
) implements MaidDomainEvent {
}
