package io.github.ozozorz.aipartner.executor;

/**
 * 以世界 game time 管理可暂停、可持久化剩余量的任务超时预算。
 */
public final class GameTimeDeadline {
    private long deadlineGameTime;
    private boolean active;

    /** 为新任务启动完整 tick 预算。 */
    public void start(long currentGameTime, long timeoutTicks) {
        restore(currentGameTime, timeoutTicks, timeoutTicks);
    }

    /**
     * 从存档恢复剩余预算；损坏的超大值会被限制到契约声明的完整预算。
     */
    public void restore(long currentGameTime, long maximumTimeoutTicks, long savedRemainingTicks) {
        if (maximumTimeoutTicks <= 0L) {
            throw new IllegalArgumentException("Timeout budget must be positive");
        }
        long remaining = Math.max(0L, Math.min(maximumTimeoutTicks, savedRemainingTicks));
        deadlineGameTime = saturatedAdd(currentGameTime, remaining);
        active = true;
    }

    public boolean isExpired(long currentGameTime) {
        return active && currentGameTime >= deadlineGameTime;
    }

    /** GUI 暂停期间同步延后截止 tick，使剩余执行预算保持不变。 */
    public void pauseOneTick() {
        if (active && deadlineGameTime < Long.MAX_VALUE) {
            deadlineGameTime++;
        }
    }

    /** 返回可写入任务快照的非负剩余 tick 数。 */
    public long remainingTicks(long currentGameTime) {
        return active ? Math.max(0L, deadlineGameTime - currentGameTime) : 0L;
    }

    public void clear() {
        active = false;
        deadlineGameTime = 0L;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
