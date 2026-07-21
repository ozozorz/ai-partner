package io.github.ozozorz.aipartner.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * 验证长期指令与旧 JobType/PartnerMode 的迁移映射。
 */
class ManualDirectiveTest {
    @Test
    void mapsOnlyLongLivedJobsToDirectives() {
        assertEquals(ManualDirective.FOLLOW, ManualDirective.fromJobType(JobType.FOLLOW).orElseThrow());
        assertEquals(ManualDirective.STAY, ManualDirective.fromJobType(JobType.STAY).orElseThrow());
        assertTrue(ManualDirective.fromJobType(JobType.COLLECT_BLOCK).isEmpty());
        assertTrue(ManualDirective.fromJobType(JobType.CANCEL).isEmpty());
    }

    @Test
    void migratesLegacyDisplayModesSafely() {
        assertEquals(ManualDirective.FOLLOW, ManualDirective.fromLegacyMode(PartnerMode.FOLLOWING));
        assertEquals(ManualDirective.STAY, ManualDirective.fromLegacyMode(PartnerMode.STAYING));
        assertEquals(ManualDirective.NONE, ManualDirective.fromLegacyMode(PartnerMode.COLLECTING));
        assertEquals(ManualDirective.NONE, ManualDirective.fromName("UNKNOWN"));
        assertEquals(PartnerMode.RETURNING_HOME, ManualDirective.RETURN_HOME.displayedMode());
    }
}
