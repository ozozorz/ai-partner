package io.github.ozozorz.aipartner.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * 验证公开长期模式只剩 FOLLOW、STAY、WORK，并可迁移旧存档名称。
 */
class PartnerModeTest {
    @Test
    void exposesExactlyThreeLongTermModes() {
        assertEquals(
                EnumSet.of(PartnerMode.FOLLOW, PartnerMode.STAY, PartnerMode.WORK),
                EnumSet.allOf(PartnerMode.class)
        );
    }

    @Test
    void migratesLegacyDisplayModes() {
        assertEquals(PartnerMode.FOLLOW, PartnerMode.fromSavedName("FOLLOWING"));
        assertEquals(PartnerMode.STAY, PartnerMode.fromSavedName("STAYING"));
        assertEquals(PartnerMode.WORK, PartnerMode.fromSavedName("COLLECTING"));
        assertEquals(PartnerMode.WORK, PartnerMode.fromSavedName("CANCEL"));
        assertTrue(PartnerMode.parse("work").isPresent());
        assertTrue(PartnerMode.parse("cancel").isEmpty());
    }
}
