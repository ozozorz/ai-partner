package io.github.ozozorz.aipartner.entity;

/**
 * 玩家可直接控制的女仆长期模式。
 *
 * <p>战斗、睡眠和具体工作阶段属于临时活动，不再伪装成可选择的模式。</p>
 */
public enum PartnerMode {
    FOLLOW,
    STAY,
    WORK;

    /**
     * 从新旧存档文本中安全恢复模式；旧的空闲和任务状态统一迁移到 WORK。
     */
    public static PartnerMode fromSavedName(String name) {
        if (name == null) {
            return WORK;
        }
        return switch (name) {
            case "FOLLOW", "FOLLOWING" -> FOLLOW;
            case "STAY", "STAYING" -> STAY;
            default -> WORK;
        };
    }

    /**
     * 解析玩家命令中稳定的小写名称。
     */
    public static java.util.Optional<PartnerMode> parse(String name) {
        try {
            return java.util.Optional.of(valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return java.util.Optional.empty();
        }
    }
}
