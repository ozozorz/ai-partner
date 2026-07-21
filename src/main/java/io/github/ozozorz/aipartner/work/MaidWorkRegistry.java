package io.github.ozozorz.aipartner.work;

import io.github.ozozorz.aipartner.work.rule.AgricultureWorkRules;
import io.github.ozozorz.aipartner.work.rule.AnimalCareWorkRules;
import io.github.ozozorz.aipartner.work.rule.ComplexWorkRules;
import io.github.ozozorz.aipartner.work.rule.EnvironmentWorkRules;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 冻结的持续工作注册表；新增能力不需要修改实体或通用工作状态机。
 */
public final class MaidWorkRegistry {
    private final Map<MaidWorkMode, MaidWorkRule> rules;

    private MaidWorkRegistry(Map<MaidWorkMode, MaidWorkRule> rules) {
        this.rules = Collections.unmodifiableMap(new EnumMap<>(rules));
    }

    public Optional<MaidWorkRule> ruleFor(MaidWorkMode mode) {
        return Optional.ofNullable(rules.get(mode));
    }

    public Collection<MaidWorkMode> registeredModes() {
        return rules.keySet();
    }

    public static MaidWorkRegistry createDefault() {
        EnumMap<MaidWorkMode, MaidWorkRule> rules = new EnumMap<>(MaidWorkMode.class);
        registerAll(rules, AgricultureWorkRules.create());
        registerAll(rules, AnimalCareWorkRules.create());
        registerAll(rules, EnvironmentWorkRules.create());
        registerAll(rules, ComplexWorkRules.create());
        return new MaidWorkRegistry(rules);
    }

    private static void registerAll(Map<MaidWorkMode, MaidWorkRule> target, Collection<MaidWorkRule> rules) {
        for (MaidWorkRule rule : rules) {
            MaidWorkRule previous = target.putIfAbsent(rule.mode(), rule);
            if (previous != null) {
                throw new IllegalStateException("Duplicate work rule for " + rule.mode());
            }
        }
    }
}
