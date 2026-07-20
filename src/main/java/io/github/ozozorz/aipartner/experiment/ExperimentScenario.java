package io.github.ozozorz.aipartner.experiment;

/**
 * 冻结实验场景的世界布置、参考指令、期望终态与可选运行中扰动。
 */
public record ExperimentScenario(
        String id,
        String instruction,
        String expectedOutcome,
        Setup setup,
        Disturbance disturbance
) {
    /**
     * 场景重置器支持的有限初始世界状态。
     */
    public enum Setup {
        COLLECT_NORMAL,
        DEPOSIT_NORMAL,
        COMPOSITE_NORMAL,
        COLLECT_TARGET_ABSENT,
        COLLECT_MISSING_TOOL,
        COLLECT_INVENTORY_FULL,
        COLLECT_UNREACHABLE,
        DEPOSIT_MISSING_ITEM,
        DEPOSIT_CHEST_ABSENT,
        DEPOSIT_CHEST_FULL,
        COMPOSITE_CHEST_FULL,
        COMPOSITE_INSUFFICIENT_TARGET,
        CANCEL_COLLECT,
        BOUNDARY_QUANTITY,
        BOUNDARY_RADIUS,
        TARGET_REMOVED_AFTER_ACCEPT,
        CHEST_REMOVED_AFTER_ACCEPT,
        RECOVERABLE_TARGET_CHANGE
    }

    /**
     * `/maid experiment disturb` 可执行的受限运行中变化。
     */
    public enum Disturbance {
        NONE,
        REMOVE_ALL_TARGETS,
        REMOVE_CHEST,
        REMOVE_NEAREST_TARGETS
    }
}
