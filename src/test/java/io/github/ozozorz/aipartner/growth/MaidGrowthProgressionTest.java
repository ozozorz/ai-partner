package io.github.ozozorz.aipartner.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证成长只提供有上限的温和数值增益，不参与工作解锁。 */
class MaidGrowthProgressionTest {
    @Test
    void mapsExperienceToBoundedLevel() {
        assertEquals(1, MaidGrowthProgression.levelForExperience(0));
        assertEquals(2, MaidGrowthProgression.levelForExperience(25));
        assertEquals(4, MaidGrowthProgression.levelForExperience(225));
        assertEquals(MaidGrowthProgression.MAX_LEVEL, MaidGrowthProgression.levelForExperience(Integer.MAX_VALUE));
    }

    @Test
    void capsHighLevelEffectsAndCooldownReduction() {
        MaidGrowthProgression.Effects effects = MaidGrowthProgression.effectsForLevel(20);

        assertEquals(29.5, effects.maxHealth(), 0.0001);
        assertEquals(7.8, effects.attackDamage(), 0.0001);
        assertEquals(0.3475, effects.movementSpeed(), 0.0001);
        assertEquals(0.25, effects.workCooldownReduction(), 0.0001);
        assertEquals(3, effects.extraPathRetries());
        assertEquals(75, MaidGrowthProgression.adjustCooldown(100, 20));
    }
}
