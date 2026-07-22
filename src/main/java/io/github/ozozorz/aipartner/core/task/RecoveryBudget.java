package io.github.ozozorz.aipartner.core.task;

/**
 * 记录单个契约已消耗的局部恢复次数，并在达到失败策略上限后拒绝继续恢复。
 */
public final class RecoveryBudget {
    private int consumed;

    /** 尝试消耗一次恢复预算；预算为零或耗尽时返回 false。 */
    public boolean tryConsume(int maximum) {
        if (maximum <= 0 || consumed >= maximum) {
            return false;
        }
        consumed++;
        return true;
    }

    /** 新契约开始时清空上一契约的恢复计数。 */
    public void reset() {
        consumed = 0;
    }

    /** 从存档恢复已使用次数；负值按零处理。 */
    public void restore(int savedConsumed) {
        consumed = Math.max(0, savedConsumed);
    }

    public int consumed() {
        return consumed;
    }
}
