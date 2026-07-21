package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;

/**
 * 通用物流任务使用独立任务 ID，但复用经过容量、权限和动态复验的存箱状态机。
 */
public final class TransferItemMaidTask extends DepositItemMaidTask {
    public static final String ID = "transfer_item";

    public TransferItemMaidTask(AiPartnerEntity partner) {
        super(partner, ID);
    }
}
