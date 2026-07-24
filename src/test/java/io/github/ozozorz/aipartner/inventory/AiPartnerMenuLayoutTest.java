package io.github.ozozorz.aipartner.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证菜单只包含女仆装备/储物和玩家背包，不再暴露 2×2 制作槽。 */
class AiPartnerMenuLayoutTest {
    @Test
    void partitionsPartnerAndPlayerSlotsWithoutCraftingSlots() {
        assertEquals(36, AiPartnerMenu.STORAGE_SLOT_COUNT);
        assertEquals(5, AiPartnerMenu.EQUIPMENT_SLOT_COUNT);
        assertEquals(41, AiPartnerMenu.PARTNER_SLOT_END);
        assertEquals(AiPartnerMenu.PARTNER_SLOT_END, AiPartnerMenu.PLAYER_SLOT_START);
        assertEquals(36, AiPartnerMenu.PLAYER_SLOT_END - AiPartnerMenu.PLAYER_SLOT_START);
        assertEquals(382, AiPartnerMenu.SCREEN_WIDTH);
        assertEquals(230, AiPartnerMenu.SCREEN_HEIGHT);
    }
}
