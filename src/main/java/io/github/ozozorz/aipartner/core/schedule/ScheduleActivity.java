package io.github.ozozorz.aipartner.core.schedule;

import io.github.ozozorz.aipartner.entity.PartnerMode;

/**
 * 日程在当前时间片期望女仆执行的长期活动。
 */
public enum ScheduleActivity {
    WORK(PartnerMode.WORKING),
    LEISURE(PartnerMode.RELAXING),
    SLEEP(PartnerMode.SLEEPING);

    private final PartnerMode displayedMode;

    ScheduleActivity(PartnerMode displayedMode) {
        this.displayedMode = displayedMode;
    }

    public PartnerMode displayedMode() {
        return displayedMode;
    }
}
