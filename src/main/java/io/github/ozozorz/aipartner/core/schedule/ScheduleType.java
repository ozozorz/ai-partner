package io.github.ozozorz.aipartner.core.schedule;

/**
 * 女仆可选择的三种服务端日程预设。
 */
public enum ScheduleType {
    DAY_SHIFT,
    NIGHT_SHIFT,
    ALL_DAY;

    /**
     * 从存档或命令文本安全恢复日程类型。
     */
    public static ScheduleType fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return DAY_SHIFT;
        }
    }

    public ScheduleType next() {
        ScheduleType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
