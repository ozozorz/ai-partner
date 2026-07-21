package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/**
 * 有限任务可以访问的服务端上下文，避免任务自行查找全局状态。
 */
public record MaidTaskContext(
        AiPartnerEntity partner,
        TaskContract contract,
        MaidTaskResultSink resultSink
) {
    public MaidTaskContext {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(resultSink, "resultSink");
    }

    /**
     * 返回服务端世界；客户端误启动任务时立即失败。
     */
    public ServerLevel serverLevel() {
        if (partner.level() instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        throw new IllegalStateException("Maid tasks may only run on the server");
    }
}
