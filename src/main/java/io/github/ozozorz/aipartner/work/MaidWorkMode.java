package io.github.ozozorz.aipartner.work;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 玩家为女仆选择的持续工作策略；具体动作由注册表中的工作规则提供。
 */
public enum MaidWorkMode {
    NONE("none"),
    FARMER("farmer"),
    SUGAR_CANE("sugar-cane"),
    MELON("melon"),
    COCOA("cocoa"),
    FORAGER("forager"),
    SNOW_CLEARER("snow-clearer"),
    BEEKEEPER("beekeeper"),
    SHEARER("shearer"),
    MILKER("milker"),
    CAREGIVER("caregiver"),
    BREEDER("breeder"),
    TORCH_BEARER("torch-bearer"),
    FIREFIGHTER("firefighter"),
    LUMBERJACK("lumberjack"),
    MINER("miner"),
    SMELTER("smelter"),
    FISHER("fisher");

    private final String serializedName;

    MaidWorkMode(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public MaidWorkMode next() {
        MaidWorkMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    public static Optional<MaidWorkMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.serializedName.equals(normalized))
                .findFirst();
    }

    public static MaidWorkMode fromSavedName(String value) {
        return parse(value).orElse(NONE);
    }
}
