package io.github.ozozorz.aipartner.control;

import java.util.Objects;

/**
 * 标识一次语义动作的原始文本与可信入口。
 */
public record MaidActionRequest(String rawInstruction, String sourceId) {
    public MaidActionRequest {
        rawInstruction = bounded(rawInstruction, 512);
        sourceId = bounded(Objects.requireNonNullElse(sourceId, "UNKNOWN"), 64);
        if (sourceId.isBlank()) {
            sourceId = "UNKNOWN";
        }
    }

    /**
     * 创建由命令、菜单或本地解析器发起的直接调用。
     */
    public static MaidActionRequest direct(String rawInstruction, String sourceId) {
        return new MaidActionRequest(rawInstruction, sourceId);
    }

    private static String bounded(String value, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
