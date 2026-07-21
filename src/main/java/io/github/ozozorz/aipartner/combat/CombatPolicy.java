package io.github.ozozorz.aipartner.combat;

import java.util.Locale;
import java.util.Optional;

/**
 * 女仆防御目标来源策略；玩家永远不会成为自动战斗目标。
 */
public enum CombatPolicy {
    OFF("off"),
    SELF_DEFENSE("self-defense"),
    DEFEND_OWNER("defend-owner");

    private final String serializedName;

    CombatPolicy(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public CombatPolicy next() {
        CombatPolicy[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<CombatPolicy> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (CombatPolicy policy : values()) {
            if (policy.serializedName.equals(normalized)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    public static CombatPolicy fromSavedName(String value) {
        return parse(value).orElse(DEFEND_OWNER);
    }
}
