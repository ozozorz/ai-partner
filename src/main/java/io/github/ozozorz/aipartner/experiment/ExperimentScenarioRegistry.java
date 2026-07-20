package io.github.ozozorz.aipartner.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;

/**
 * 保存论文游戏内评测所使用的 18 个冻结场景定义。
 */
public final class ExperimentScenarioRegistry {
    private static final Map<String, ExperimentScenario> SCENARIOS = createScenarios();

    private ExperimentScenarioRegistry() {
    }

    public static Optional<ExperimentScenario> find(String id) {
        return Optional.ofNullable(SCENARIOS.get(id));
    }

    public static List<ExperimentScenario> all() {
        return List.copyOf(SCENARIOS.values());
    }

    public static List<String> ids() {
        return List.copyOf(SCENARIOS.keySet());
    }

    private static Map<String, ExperimentScenario> createScenarios() {
        List<ExperimentScenario> definitions = List.of(
                scenario("collect_normal", "收集 8 个橡木原木", "COMPLETED", ExperimentScenario.Setup.COLLECT_NORMAL),
                scenario("deposit_normal", "把 8 个橡木原木放进箱子", "COMPLETED", ExperimentScenario.Setup.DEPOSIT_NORMAL),
                scenario("composite_normal", "砍 8 个橡木原木然后放进箱子", "COMPLETED", ExperimentScenario.Setup.COMPOSITE_NORMAL),
                scenario("collect_target_absent", "收集 8 个橡木原木", "TARGET_NOT_FOUND", ExperimentScenario.Setup.COLLECT_TARGET_ABSENT),
                scenario("collect_missing_tool", "收集 8 个橡木原木", "MISSING_TOOL", ExperimentScenario.Setup.COLLECT_MISSING_TOOL),
                scenario("collect_inventory_full", "收集 8 个橡木原木", "INVENTORY_FULL", ExperimentScenario.Setup.COLLECT_INVENTORY_FULL),
                scenario("collect_unreachable", "收集 1 个橡木原木", "TARGET_NOT_FOUND_OR_TIMEOUT", ExperimentScenario.Setup.COLLECT_UNREACHABLE),
                scenario("deposit_missing_item", "把 8 个橡木原木放进箱子", "MISSING_ITEM", ExperimentScenario.Setup.DEPOSIT_MISSING_ITEM),
                scenario("deposit_chest_absent", "把 8 个橡木原木放进箱子", "TARGET_NOT_FOUND", ExperimentScenario.Setup.DEPOSIT_CHEST_ABSENT),
                scenario("deposit_chest_full", "把 8 个橡木原木放进箱子", "CONTAINER_FULL", ExperimentScenario.Setup.DEPOSIT_CHEST_FULL),
                scenario("composite_chest_full", "砍 8 个橡木原木然后放进箱子", "CONTAINER_FULL", ExperimentScenario.Setup.COMPOSITE_CHEST_FULL),
                scenario("composite_insufficient_target", "砍 8 个橡木原木然后放进箱子", "TARGET_NOT_FOUND", ExperimentScenario.Setup.COMPOSITE_INSUFFICIENT_TARGET),
                scenario("cancel_collect", "收集 8 个橡木原木，然后取消任务", "CANCELLED", ExperimentScenario.Setup.CANCEL_COLLECT),
                scenario("boundary_quantity", "收集 64 个橡木原木", "COMPLETED", ExperimentScenario.Setup.BOUNDARY_QUANTITY),
                scenario("boundary_radius", "在 24 格范围内收集 1 个橡木原木", "COMPLETED", ExperimentScenario.Setup.BOUNDARY_RADIUS),
                new ExperimentScenario(
                        "target_removed_after_accept",
                        "收集 8 个橡木原木",
                        "TARGET_NOT_FOUND",
                        ExperimentScenario.Setup.TARGET_REMOVED_AFTER_ACCEPT,
                        ExperimentScenario.Disturbance.REMOVE_ALL_TARGETS
                ),
                new ExperimentScenario(
                        "chest_removed_after_accept",
                        "把 8 个橡木原木放进箱子",
                        "TARGET_NOT_FOUND",
                        ExperimentScenario.Setup.CHEST_REMOVED_AFTER_ACCEPT,
                        ExperimentScenario.Disturbance.REMOVE_CHEST
                ),
                new ExperimentScenario(
                        "recoverable_target_change",
                        "收集 8 个橡木原木",
                        "COMPLETED_AFTER_RETRY",
                        ExperimentScenario.Setup.RECOVERABLE_TARGET_CHANGE,
                        ExperimentScenario.Disturbance.REMOVE_NEAREST_TARGETS
                )
        );
        LinkedHashMap<String, ExperimentScenario> scenarios = new LinkedHashMap<>();
        for (ExperimentScenario definition : definitions) {
            if (scenarios.put(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate experiment scenario id " + definition.id());
            }
        }
        return Collections.unmodifiableMap(scenarios);
    }

    private static ExperimentScenario scenario(
            String id,
            String instruction,
            String expectedOutcome,
            ExperimentScenario.Setup setup
    ) {
        return new ExperimentScenario(
                id,
                instruction,
                expectedOutcome,
                setup,
                ExperimentScenario.Disturbance.NONE
        );
    }
}
