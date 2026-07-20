package io.github.ozozorz.aipartner.entity;

/**
 * 实体当前的低层行为模式；只允许确定性执行器修改。
 */
public enum PartnerMode {
    IDLE,
    FOLLOWING,
    STAYING,
    COLLECTING,
    DEPOSITING;

    /**
     * 从持久化文本中安全恢复模式。
     */
    public static PartnerMode fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException exception) {
            return IDLE;
        }
    }
}
