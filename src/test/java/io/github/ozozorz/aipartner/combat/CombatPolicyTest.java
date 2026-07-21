package io.github.ozozorz.aipartner.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证防御策略的命令别名、循环顺序和未知存档回退。
 */
class CombatPolicyTest {
    @Test
    void parsesAndCyclesPoliciesDeterministically() {
        assertEquals(CombatPolicy.SELF_DEFENSE, CombatPolicy.parse("self_defense").orElseThrow());
        assertEquals(CombatPolicy.DEFEND_OWNER, CombatPolicy.SELF_DEFENSE.next());
        assertEquals(CombatPolicy.OFF, CombatPolicy.DEFEND_OWNER.next());
        assertTrue(CombatPolicy.parse("attack-everything").isEmpty());
    }

    @Test
    void unknownSavedPolicyFallsBackToOwnerDefense() {
        assertEquals(CombatPolicy.DEFEND_OWNER, CombatPolicy.fromSavedName("future-policy"));
    }
}
