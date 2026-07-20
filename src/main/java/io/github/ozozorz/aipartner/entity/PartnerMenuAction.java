package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.job.JobType;
import java.util.Arrays;
import java.util.Optional;

/**
 * 女仆背包界面允许触发的服务端行为白名单。
 *
 * <p>按钮只映射到无参数基础任务，带目标和数量的工作仍必须经过命令或 LLM 契约编译。</p>
 */
public enum PartnerMenuAction {
    FOLLOW(0, JobType.FOLLOW, "message.ai-partner.following"),
    STAY(1, JobType.STAY, "message.ai-partner.staying"),
    CANCEL(2, JobType.CANCEL, "message.ai-partner.cancelled");

    private final int buttonId;
    private final JobType jobType;
    private final String responseKey;

    PartnerMenuAction(int buttonId, JobType jobType, String responseKey) {
        this.buttonId = buttonId;
        this.jobType = jobType;
        this.responseKey = responseKey;
    }

    public int buttonId() {
        return buttonId;
    }

    public JobType jobType() {
        return jobType;
    }

    public String responseKey() {
        return responseKey;
    }

    /**
     * 将客户端按钮编号严格解析成白名单动作。
     */
    public static Optional<PartnerMenuAction> fromButtonId(int buttonId) {
        return Arrays.stream(values())
                .filter(action -> action.buttonId == buttonId)
                .findFirst();
    }
}
