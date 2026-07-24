package io.github.ozozorz.aipartner.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 女仆菜单允许发送的固定服务端动作白名单。
 */
public enum PartnerMenuAction {
    FOLLOW(0, "message.ai-partner.following"),
    STAY(1, "message.ai-partner.staying"),
    WORK(2, "message.ai-partner.working"),
    RETURN_HOME(3, "message.ai-partner.returning_home"),
    CYCLE_SCHEDULE(4, "message.ai-partner.schedule_changed"),
    TOGGLE_HOME_BOUND(5, "message.ai-partner.home_bound_changed"),
    SET_WORK_LOCATION(6, "message.ai-partner.location_changed"),
    CLEAR_WORK_LOCATION(7, "message.ai-partner.location_changed"),
    SET_LEISURE_LOCATION(8, "message.ai-partner.location_changed"),
    CLEAR_LEISURE_LOCATION(9, "message.ai-partner.location_changed"),
    SET_SLEEP_LOCATION(10, "message.ai-partner.location_changed"),
    CLEAR_SLEEP_LOCATION(11, "message.ai-partner.location_changed"),
    DECREASE_RADIUS(12, "message.ai-partner.radius_changed"),
    INCREASE_RADIUS(13, "message.ai-partner.radius_changed"),
    CYCLE_WORK_MODE(14, "message.ai-partner.work_mode_changed");

    private final int buttonId;
    private final String responseKey;

    PartnerMenuAction(int buttonId, String responseKey) {
        this.buttonId = buttonId;
        this.responseKey = responseKey;
    }

    public int buttonId() {
        return buttonId;
    }

    public String responseKey() {
        return responseKey;
    }

    public static Optional<PartnerMenuAction> fromButtonId(int buttonId) {
        return Arrays.stream(values()).filter(action -> action.buttonId == buttonId).findFirst();
    }
}
