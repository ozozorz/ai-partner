package io.github.ozozorz.aipartner.core.behavior;

import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 玩家显式设置的长期行为指令。
 *
 * <p>它与有限工作任务分离，避免把跟随和待命当作需要逐 tick 执行的工作类型。</p>
 */
public enum ManualDirective {
    NONE(null, PartnerMode.IDLE),
    FOLLOW(JobType.FOLLOW, PartnerMode.FOLLOWING),
    STAY(JobType.STAY, PartnerMode.STAYING),
    RETURN_HOME(null, PartnerMode.RETURNING_HOME);

    private final @Nullable JobType sourceJobType;
    private final PartnerMode displayedMode;

    ManualDirective(@Nullable JobType sourceJobType, PartnerMode displayedMode) {
        this.sourceJobType = sourceJobType;
        this.displayedMode = displayedMode;
    }

    public PartnerMode displayedMode() {
        return displayedMode;
    }

    /**
     * 把旧 Job DSL 中的长期任务映射为手动指令。
     */
    public static Optional<ManualDirective> fromJobType(JobType jobType) {
        return Arrays.stream(values())
                .filter(directive -> directive.sourceJobType == jobType)
                .findFirst();
    }

    /**
     * 从存档文本安全恢复，未知值回退为没有手动覆盖。
     */
    public static ManualDirective fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return NONE;
        }
    }

    /**
     * 从 v0.4 的混合模式推导长期指令，用于旧存档迁移。
     */
    public static ManualDirective fromLegacyMode(PartnerMode mode) {
        return switch (mode) {
            case FOLLOWING -> FOLLOW;
            case STAYING -> STAY;
            default -> NONE;
        };
    }
}
