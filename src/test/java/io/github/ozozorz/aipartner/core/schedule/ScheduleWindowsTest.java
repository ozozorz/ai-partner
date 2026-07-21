package io.github.ozozorz.aipartner.core.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证三种日程在全部时间边界上的确定性转换。
 */
class ScheduleWindowsTest {
    private final ScheduleWindows windows = ScheduleWindows.DEFAULT;

    @Test
    void dayShiftTransitionsThroughWorkLeisureSleepAndDawn() {
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.DAY_SHIFT, 0));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.DAY_SHIFT, 11999));
        assertEquals(ScheduleActivity.LEISURE, windows.activityAt(ScheduleType.DAY_SHIFT, 12000));
        assertEquals(ScheduleActivity.SLEEP, windows.activityAt(ScheduleType.DAY_SHIFT, 14000));
        assertEquals(ScheduleActivity.LEISURE, windows.activityAt(ScheduleType.DAY_SHIFT, 22000));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.DAY_SHIFT, 24000));
    }

    @Test
    void nightAndAllDaySchedulesUseTheSameCentralWindows() {
        assertEquals(ScheduleActivity.SLEEP, windows.activityAt(ScheduleType.NIGHT_SHIFT, 6000));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.NIGHT_SHIFT, 18000));
        assertEquals(ScheduleActivity.LEISURE, windows.activityAt(ScheduleType.NIGHT_SHIFT, 23000));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.ALL_DAY, 6000));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.ALL_DAY, 18000));
        assertEquals(ScheduleActivity.WORK, windows.activityAt(ScheduleType.ALL_DAY, 13000));
    }

    @Test
    void reportsNextBoundaryAcrossDayWrap() {
        assertEquals(1, windows.ticksUntilNextTransition(ScheduleType.DAY_SHIFT, 11999));
        assertEquals(2000, windows.ticksUntilNextTransition(ScheduleType.DAY_SHIFT, 12000));
        assertEquals(1, windows.ticksUntilNextTransition(ScheduleType.NIGHT_SHIFT, 23999));
        assertEquals(12000, windows.ticksUntilNextTransition(ScheduleType.NIGHT_SHIFT, 24000));
        assertEquals(-1, windows.ticksUntilNextTransition(ScheduleType.ALL_DAY, 13000));
    }

    @Test
    void rejectsUnorderedWindows() {
        assertThrows(IllegalArgumentException.class, () -> new ScheduleWindows(0, 14000, 12000, 22000, 24000));
    }
}
