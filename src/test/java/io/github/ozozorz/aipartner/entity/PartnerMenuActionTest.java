package io.github.ozozorz.aipartner.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * 验证客户端按钮编号只能映射到允许的无参数任务。
 */
class PartnerMenuActionTest {
    @Test
    void mapsWhitelistedButtonsToExpectedJobs() {
        assertEquals(JobType.FOLLOW, PartnerMenuAction.fromButtonId(0).orElseThrow().jobType());
        assertEquals(JobType.STAY, PartnerMenuAction.fromButtonId(1).orElseThrow().jobType());
        assertEquals(JobType.CANCEL, PartnerMenuAction.fromButtonId(2).orElseThrow().jobType());
    }

    @Test
    void rejectsUnknownButtonIds() {
        assertTrue(PartnerMenuAction.fromButtonId(-1).isEmpty());
        assertTrue(PartnerMenuAction.fromButtonId(3).isEmpty());
        assertTrue(PartnerMenuAction.fromButtonId(Integer.MAX_VALUE).isEmpty());
    }
}
