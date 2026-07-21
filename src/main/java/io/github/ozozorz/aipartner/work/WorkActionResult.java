package io.github.ozozorz.aipartner.work;

/**
 * 工作规则执行一个原子动作后的有限结果。
 */
public enum WorkActionResult {
    SUCCESS,
    RETRY,
    BLOCKED
}
