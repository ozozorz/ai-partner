package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.job.JobType;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 女仆 GUI 可发送的服务端白名单动作；带参数的任务仍不能由任意按钮数据构造。
 */
public enum PartnerMenuAction {
    FOLLOW(0, JobType.FOLLOW, "message.ai-partner.following"),
    STAY(1, JobType.STAY, "message.ai-partner.staying"),
    CANCEL(2, JobType.CANCEL, "message.ai-partner.cancelled"),
    RETURN_HOME(3, null, "message.ai-partner.returning_home"),
    CYCLE_SCHEDULE(4, null, "message.ai-partner.schedule_changed"),
    TOGGLE_HOME_BOUND(5, null, "message.ai-partner.home_bound_changed"),
    SET_WORK_LOCATION(6, null, "message.ai-partner.location_changed"),
    CLEAR_WORK_LOCATION(7, null, "message.ai-partner.location_changed"),
    SET_LEISURE_LOCATION(8, null, "message.ai-partner.location_changed"),
    CLEAR_LEISURE_LOCATION(9, null, "message.ai-partner.location_changed"),
    SET_SLEEP_LOCATION(10, null, "message.ai-partner.location_changed"),
    CLEAR_SLEEP_LOCATION(11, null, "message.ai-partner.location_changed"),
    DECREASE_RADIUS(12, null, "message.ai-partner.radius_changed"),
    INCREASE_RADIUS(13, null, "message.ai-partner.radius_changed"),
    CYCLE_WORK_MODE(14, null, "message.ai-partner.work_mode_changed"),
    CYCLE_COMBAT_POLICY(15, null, "message.ai-partner.combat_policy_changed");

    private final int buttonId;
    private final @Nullable JobType jobType;
    private final String responseKey;

    PartnerMenuAction(int buttonId, @Nullable JobType jobType, String responseKey) {
        this.buttonId = buttonId;
        this.jobType = jobType;
        this.responseKey = responseKey;
    }

    public int buttonId() {
        return buttonId;
    }

    public @Nullable JobType jobType() {
        return jobType;
    }

    public boolean isContractAction() {
        return jobType != null;
    }

    public String responseKey() {
        return responseKey;
    }

    public static Optional<PartnerMenuAction> fromButtonId(int buttonId) {
        return Arrays.stream(values())
                .filter(action -> action.buttonId == buttonId)
                .findFirst();
    }
}
