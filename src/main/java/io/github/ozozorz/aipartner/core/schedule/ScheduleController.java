package io.github.ozozorz.aipartner.core.schedule;

import java.util.Objects;

/**
 * 无世界副作用的日程计算器，导航与睡眠由生活控制器消费计算结果。
 */
public final class ScheduleController {
    private final ScheduleWindows windows;

    public ScheduleController(ScheduleWindows windows) {
        this.windows = Objects.requireNonNull(windows, "windows");
    }

    public ScheduleActivity activityAt(ScheduleType type, long dayTime) {
        return windows.activityAt(type, dayTime);
    }

    public int ticksUntilNextTransition(ScheduleType type, long dayTime) {
        return windows.ticksUntilNextTransition(type, dayTime);
    }
}
