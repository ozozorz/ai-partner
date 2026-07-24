package io.github.ozozorz.aipartner.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证客户端按钮编号只能映射到新的固定动作白名单。
 */
class PartnerMenuActionTest {
    @Test
    void mapsModeButtonsWithoutCancelOrContracts() {
        assertEquals(PartnerMenuAction.FOLLOW, PartnerMenuAction.fromButtonId(0).orElseThrow());
        assertEquals(PartnerMenuAction.STAY, PartnerMenuAction.fromButtonId(1).orElseThrow());
        assertEquals(PartnerMenuAction.WORK, PartnerMenuAction.fromButtonId(2).orElseThrow());
        assertEquals(PartnerMenuAction.RETURN_HOME, PartnerMenuAction.fromButtonId(3).orElseThrow());
    }

    @Test
    void rejectsUnknownButtonIds() {
        assertTrue(PartnerMenuAction.fromButtonId(-1).isEmpty());
        assertEquals(PartnerMenuAction.CYCLE_WORK_MODE, PartnerMenuAction.fromButtonId(14).orElseThrow());
        assertTrue(PartnerMenuAction.fromButtonId(15).isEmpty());
        assertTrue(PartnerMenuAction.fromButtonId(Integer.MAX_VALUE).isEmpty());
    }
}
