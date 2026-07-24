package io.github.ozozorz.aipartner.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 验证基础技能目录覆盖制作、工具、容器、熔炉、放置、生产和战斗能力。
 */
class MaidSkillTypeTest {
    @Test
    void serializedNamesAreUniqueAndRoundTrip() {
        Set<String> names = Arrays.stream(MaidSkillType.values())
                .map(MaidSkillType::serializedName)
                .collect(Collectors.toSet());
        assertEquals(MaidSkillType.values().length, names.size());
        for (MaidSkillType skill : MaidSkillType.values()) {
            assertEquals(skill, MaidSkillType.parse(skill.serializedName()).orElseThrow());
        }
    }

    @Test
    void containsRequestedCoreCapabilities() {
        assertTrue(MaidSkillType.parse("personal-crafting-2x2").isPresent());
        assertTrue(MaidSkillType.parse("workbench-crafting-3x3").isPresent());
        assertTrue(MaidSkillType.parse("mine-with-pickaxe").isPresent());
        assertTrue(MaidSkillType.parse("smelt-in-furnace").isPresent());
        assertTrue(MaidSkillType.parse("remember-container-contents").isPresent());
        assertTrue(MaidSkillType.parse("shield-block").isPresent());
    }
}
