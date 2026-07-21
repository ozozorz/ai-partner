package io.github.ozozorz.aipartner.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证好感度与成长经验保持独立并具有有界累加语义。
 */
class MaidGrowthDataTest {
    @Test
    void tracksAffectionAndExperienceIndependently() {
        MaidGrowthData data = new MaidGrowthData();

        data.addAffection(12);
        data.addExperience(225);

        assertEquals(12, data.affection());
        assertEquals(225, data.experience());
        assertEquals(4, data.level());
    }

    @Test
    void clampsAffectionAndIgnoresNegativeGains() {
        MaidGrowthData data = new MaidGrowthData();

        data.addAffection(5000);
        data.addAffection(-20);
        data.addExperience(-100);

        assertEquals(MaidGrowthData.MAX_AFFECTION, data.affection());
        assertEquals(0, data.experience());
    }

    @Test
    void grantsFirstCompletionOnlyOncePerWorkMode() {
        MaidGrowthData data = new MaidGrowthData();

        assertTrue(data.markFirstWorkCompletion(3));
        assertFalse(data.markFirstWorkCompletion(3));
        assertTrue(data.markFirstWorkCompletion(4));
    }
}
