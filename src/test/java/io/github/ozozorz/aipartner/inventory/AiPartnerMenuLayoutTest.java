package io.github.ozozorz.aipartner.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 防止新增制作槽后女仆、制作区和玩家背包的菜单索引发生重叠。 */
class AiPartnerMenuLayoutTest {
    @Test
    void partitionsPartnerCraftingAndPlayerSlotsWithoutOverlap() {
        assertEquals(AiPartnerMenu.PARTNER_SLOT_END, AiPartnerMenu.CRAFT_INPUT_SLOT_START);
        assertEquals(4, AiPartnerMenu.CRAFT_INPUT_SLOT_END - AiPartnerMenu.CRAFT_INPUT_SLOT_START);
        assertEquals(AiPartnerMenu.CRAFT_INPUT_SLOT_END, AiPartnerMenu.CRAFT_RESULT_SLOT);
        assertEquals(AiPartnerMenu.CRAFT_RESULT_SLOT + 1, AiPartnerMenu.PLAYER_SLOT_START);
        assertEquals(36, AiPartnerMenu.PLAYER_SLOT_END - AiPartnerMenu.PLAYER_SLOT_START);
        assertEquals(382, AiPartnerMenu.SCREEN_WIDTH);
        assertEquals(230, AiPartnerMenu.SCREEN_HEIGHT);
    }
}
