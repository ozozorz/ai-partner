package io.github.ozozorz.aipartner.entity;

/**
 * 面向客户端、命令和旧存档的有效行为投影。
 *
 * <p>权威状态由 ManualDirective 和 MaidTaskRuntime 分开保存，业务代码不得直接修改本枚举。</p>
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
