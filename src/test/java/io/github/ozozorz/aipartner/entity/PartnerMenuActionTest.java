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
        assertEquals(PartnerMenuAction.RETURN_HOME, PartnerMenuAction.fromButtonId(3).orElseThrow());
        assertTrue(!PartnerMenuAction.fromButtonId(3).orElseThrow().isContractAction());
    }

    @Test
    void rejectsUnknownButtonIds() {
        assertTrue(PartnerMenuAction.fromButtonId(-1).isEmpty());
        assertEquals(PartnerMenuAction.CYCLE_WORK_MODE, PartnerMenuAction.fromButtonId(14).orElseThrow());
        assertEquals(PartnerMenuAction.CYCLE_COMBAT_POLICY, PartnerMenuAction.fromButtonId(15).orElseThrow());
        assertTrue(PartnerMenuAction.fromButtonId(16).isEmpty());
        assertTrue(PartnerMenuAction.fromButtonId(Integer.MAX_VALUE).isEmpty());
    }
}
