package io.github.ozozorz.aipartner.core.event;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;

/**
 * 女仆拾取经验后公开的只读成长事件。
 */
public record MaidExperienceEvent(
        AiPartnerEntity partner,
        int orbExperience,
        int repairedDurability,
        int growthExperience
) implements MaidDomainEvent {
}
