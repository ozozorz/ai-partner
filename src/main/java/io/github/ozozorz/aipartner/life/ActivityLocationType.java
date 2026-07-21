package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;

/**
 * GUI 和命令可配置的三类活动地点。
 */
public enum ActivityLocationType {
    WORK,
    LEISURE,
    SLEEP;

    public static ActivityLocationType fromName(String name) {
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Unknown activity location type: " + name, exception);
        }
    }

    public static ActivityLocationType fromActivity(ScheduleActivity activity) {
        return switch (activity) {
            case WORK -> WORK;
            case LEISURE -> LEISURE;
            case SLEEP -> SLEEP;
        };
    }
}
