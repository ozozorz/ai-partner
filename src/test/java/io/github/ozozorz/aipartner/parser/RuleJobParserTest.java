package io.github.ozozorz.aipartner.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * 验证无 LLM 时规则基线的稳定中文与英文映射。
 */
class RuleJobParserTest {
    @Test
    void parsesChineseFollowInstruction() {
        JobSpec result = RuleJobParser.parse("请跟着我走").orElseThrow();

        assertEquals(JobType.FOLLOW, result.type());
    }

    @Test
    void parsesChineseCollectInstruction() {
        JobSpec result = RuleJobParser.parse("帮我收集 8 块橡木原木").orElseThrow();

        assertEquals(JobType.COLLECT_BLOCK, result.type());
        assertEquals("minecraft:oak_log", result.target());
        assertEquals(8, result.quantity());
        assertEquals(16, result.radius());
    }

    @Test
    void parsesChineseDepositInstruction() {
        JobSpec result = RuleJobParser.parse("把八个白桦原木放进箱子").orElseThrow();

        assertEquals(JobType.DEPOSIT_ITEM, result.type());
        assertEquals("minecraft:birch_log", result.target());
        assertEquals(8, result.quantity());
    }

    @Test
    void ambiguousCollectionNeedsClarification() {
        assertTrue(RuleJobParser.parse("帮我弄一些木头").isEmpty());
    }
}

