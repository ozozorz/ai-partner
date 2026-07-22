package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import org.junit.jupiter.api.Test;

/** Covers offline parsing beyond the legacy bounded job grammar. */
class LocalMaidIntentParserTest {
    @Test
    void parsesWorkScheduleCombatAndQueries() {
        MaidControlIntent.SetWorkMode work = assertInstanceOf(
                MaidControlIntent.SetWorkMode.class,
                LocalMaidIntentParser.parse("please start mining").intent()
        );
        assertEquals(MaidWorkMode.MINER, work.mode());

        MaidControlIntent.SetSchedule schedule = assertInstanceOf(
                MaidControlIntent.SetSchedule.class,
                LocalMaidIntentParser.parse("night shift").intent()
        );
        assertEquals(ScheduleType.NIGHT_SHIFT, schedule.schedule());

        MaidControlIntent.SetCombatPolicy combat = assertInstanceOf(
                MaidControlIntent.SetCombatPolicy.class,
                LocalMaidIntentParser.parse("self defense only").intent()
        );
        assertEquals(CombatPolicy.SELF_DEFENSE, combat.policy());

        assertInstanceOf(
                MaidControlIntent.QueryInventory.class,
                LocalMaidIntentParser.parse("show inventory").intent()
        );
    }

    @Test
    void parsesChinesePersistentWorkAndRename() {
        MaidControlIntent.SetWorkMode work = assertInstanceOf(
                MaidControlIntent.SetWorkMode.class,
                LocalMaidIntentParser.parse("\u53bb\u780d\u6811").intent()
        );
        assertEquals(MaidWorkMode.LUMBERJACK, work.mode());

        MaidControlIntent.Rename rename = assertInstanceOf(
                MaidControlIntent.Rename.class,
                LocalMaidIntentParser.parse("\u6539\u540d\u4e3a \u5c0f\u96ea").intent()
        );
        assertEquals("\u5c0f\u96ea", rename.name());
    }

    @Test
    void rejectsOrClarifiesUnknownCapabilities() {
        assertEquals(
                MaidControlDialogueAct.REJECT_UNSUPPORTED,
                LocalMaidIntentParser.parse("build a castle").dialogueAct()
        );
        assertEquals(
                MaidControlDialogueAct.ASK_CLARIFICATION,
                LocalMaidIntentParser.parse("do something useful").dialogueAct()
        );
    }
}
