package io.github.ozozorz.aipartner.work.complex;

/**
 * 熔炉批次的纯数值守恒规则。
 * 用独立纯函数隔离“剩余原料 + 已产出成品 = 原计划批次”，便于重启和玩家干预测试。
 */
public final class FurnaceBatchConservation {
    private FurnaceBatchConservation() {
    }

    public static boolean respects(
            int remainingInput,
            int producedResult,
            int plannedInput,
            int resultPerInput
    ) {
        if (remainingInput < 0 || producedResult < 0 || plannedInput <= 0 || resultPerInput <= 0) {
            return false;
        }
        if (remainingInput > plannedInput
                || producedResult > plannedInput * resultPerInput
                || producedResult % resultPerInput != 0) {
            return false;
        }
        return remainingInput + producedResult / resultPerInput == plannedInput;
    }
}
