package io.github.ozozorz.aipartner.control;

import java.util.Locale;
import java.util.Optional;

/**
 * 女仆解释自然语言时采用的驱动方式；世界行为仍统一交给服务端确定性控制器。
 */
public enum MaidDriveMode {
    LOCAL("local"),
    LLM("llm");

    private final String serializedName;

    MaidDriveMode(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public MaidDriveMode next() {
        return this == LOCAL ? LLM : LOCAL;
    }

    public static Optional<MaidDriveMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (MaidDriveMode mode : values()) {
            if (mode.serializedName.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public static MaidDriveMode fromSavedName(String value) {
        return parse(value).orElse(LOCAL);
    }
}
