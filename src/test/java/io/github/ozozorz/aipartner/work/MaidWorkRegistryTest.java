package io.github.ozozorz.aipartner.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * 验证每个可选持续工作都由冻结注册规则提供，NONE 保持无世界副作用。
 */
class MaidWorkRegistryTest {
    @Test
    void registersEveryConcreteBasicWorkMode() {
        EnumSet<MaidWorkMode> expected = EnumSet.allOf(MaidWorkMode.class);
        expected.remove(MaidWorkMode.NONE);
        assertEquals(expected, EnumSet.copyOf(MaidWorkRegistry.createDefault().registeredModes()));
    }

    @Test
    void parsesStableCommandAndSaveNames() {
        assertEquals(MaidWorkMode.SUGAR_CANE, MaidWorkMode.parse("sugar_cane").orElseThrow());
        assertEquals(MaidWorkMode.FIREFIGHTER, MaidWorkMode.parse("firefighter").orElseThrow());
        assertEquals(MaidWorkMode.LUMBERJACK, MaidWorkMode.parse("lumberjack").orElseThrow());
        assertEquals(MaidWorkMode.SMELTER, MaidWorkMode.parse("smelter").orElseThrow());
        assertEquals(MaidWorkMode.NONE, MaidWorkMode.fromSavedName("future-mode"));
        assertTrue(MaidWorkMode.parse("").isEmpty());
    }

    @Test
    void onlyWorldMutatingWorkRequiresMobGriefing() {
        MaidWorkRegistry registry = MaidWorkRegistry.createDefault();
        assertTrue(registry.ruleFor(MaidWorkMode.LUMBERJACK).orElseThrow().requiresMobGriefing());
        assertTrue(registry.ruleFor(MaidWorkMode.MINER).orElseThrow().requiresMobGriefing());
        assertFalse(registry.ruleFor(MaidWorkMode.SMELTER).orElseThrow().requiresMobGriefing());
        assertFalse(registry.ruleFor(MaidWorkMode.FISHER).orElseThrow().requiresMobGriefing());
    }
}
