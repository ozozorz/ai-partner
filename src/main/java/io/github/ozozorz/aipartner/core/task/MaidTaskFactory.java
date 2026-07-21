package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;

/**
 * 为单个女仆创建隔离任务实例。
 */
@FunctionalInterface
public interface MaidTaskFactory {
    MaidTask create(AiPartnerEntity partner);
}
