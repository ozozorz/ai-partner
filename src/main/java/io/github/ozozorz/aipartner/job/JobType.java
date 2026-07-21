package io.github.ozozorz.aipartner.job;

/**
 * 第一版 Job DSL 的白名单任务类型。
 */
public enum JobType {
    FOLLOW,
    STAY,
    COLLECT_BLOCK,
    DEPOSIT_ITEM,
    COLLECT_AND_DEPOSIT,
    CANCEL,
    TRANSFER_ITEM
}
