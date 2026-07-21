package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;

/**
 * 一个女仆实例共享的基础动作集合。
 */
public record MaidActions(
        NavigateAction navigation,
        BreakBlockAction breakBlock,
        TransferItemAction transferItem
) {
    public MaidActions {
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(breakBlock, "breakBlock");
        Objects.requireNonNull(transferItem, "transferItem");
    }

    public static MaidActions create(AiPartnerEntity partner) {
        return new MaidActions(
                new NavigateAction(partner),
                new BreakBlockAction(partner),
                new TransferItemAction()
        );
    }
}
