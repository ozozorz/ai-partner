package io.github.ozozorz.aipartner.core.schedule;

/**
 * 集中定义一天中的日间、过渡和夜间边界，避免时间常量散落在活动行为中。
 */
public record ScheduleWindows(
        int dayStart,
        int duskStart,
        int nightStart,
        int dawnStart,
        int dayLength
) {
    public static final ScheduleWindows DEFAULT = new ScheduleWindows(0, 12000, 14000, 22000, 24000);

    public ScheduleWindows {
        if (dayLength <= 0
                || dayStart != 0
                || duskStart <= dayStart
                || nightStart <= duskStart
                || dawnStart <= nightStart
                || dawnStart >= dayLength) {
            throw new IllegalArgumentException("Schedule windows must be ordered within one Minecraft day");
        }
    }

    /**
     * 计算指定预设在世界时间下的期望活动。
     */
    public ScheduleActivity activityAt(ScheduleType type, long dayTime) {
        if (type == ScheduleType.ALL_DAY) {
            return ScheduleActivity.WORK;
        }
        int time = normalize(dayTime);
        boolean dusk = time >= duskStart && time < nightStart;
        boolean dawn = time >= dawnStart;
        if (dusk || dawn) {
            return ScheduleActivity.LEISURE;
        }
        boolean day = time >= dayStart && time < duskStart;
        return switch (type) {
            case DAY_SHIFT -> day ? ScheduleActivity.WORK : ScheduleActivity.SLEEP;
            case NIGHT_SHIFT -> day ? ScheduleActivity.SLEEP : ScheduleActivity.WORK;
            case ALL_DAY -> throw new IllegalStateException("ALL_DAY was handled before window evaluation");
        };
    }

    /**
     * 返回距离下一次活动边界的 tick 数，结果始终大于零。
     */
    public int ticksUntilNextTransition(ScheduleType type, long dayTime) {
        if (type == ScheduleType.ALL_DAY) {
            return -1;
        }
        int time = normalize(dayTime);
        int[] boundaries = {duskStart, nightStart, dawnStart, dayLength};
        for (int boundary : boundaries) {
            if (boundary > time) {
                return boundary - time;
            }
        }
        return dayLength - time;
    }

    private int normalize(long dayTime) {
        return Math.floorMod(dayTime, dayLength);
    }
}
