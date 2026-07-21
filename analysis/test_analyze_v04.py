"""analysis/analyze_v04.py 的纯函数回归测试。"""

from __future__ import annotations

import unittest

from analyze_v04 import binomial_two_sided, evaluate_offline, rule_bt_predict, wilson_interval


class RuleBaselineReplicaTest(unittest.TestCase):
    """确保 Python 复刻与 Java RuleJobParser 的关键边界保持一致。"""

    def test_parses_basic_and_composite_jobs(self) -> None:
        """基础命令和固定两阶段命令应映射到相同任务类型。"""

        self.assertEqual("FOLLOW", rule_bt_predict("跟着我")[1]["type"])
        act, job = rule_bt_predict("砍 8 个橡木原木然后放进箱子")
        self.assertEqual("PROPOSE_JOB", act)
        self.assertEqual("COLLECT_AND_DEPOSIT", job["type"])
        self.assertEqual("minecraft:oak_log", job["target"])
        self.assertEqual(8, job["quantity"])
        self.assertEqual(16, job["radius"])

    def test_preserves_explicit_radius_and_rejects_unknown_wording(self) -> None:
        """显式半径优先，规则未覆盖的口语表达进入澄清。"""

        _, job = rule_bt_predict("在 24 格内砍 3 个云杉原木")
        self.assertEqual(24, job["radius"])
        act, missing = rule_bt_predict("给我搞四个云杉原木")
        self.assertEqual("ASK_CLARIFICATION", act)
        self.assertIsNone(missing)


class StatisticalHelperTest(unittest.TestCase):
    """验证报告使用的精确检验与区间基础值。"""

    def test_exact_two_sided_binomial(self) -> None:
        """六个同方向 discordance 的双侧概率应为 0.03125。"""

        self.assertAlmostEqual(0.03125, binomial_two_sided(6, 0))
        self.assertAlmostEqual(0.5, binomial_two_sided(2, 0))
        self.assertEqual(1.0, binomial_two_sided(0, 0))

    def test_wilson_interval_contains_estimate(self) -> None:
        """Wilson 区间应覆盖点估计并保持在概率边界内。"""

        lower, upper = wilson_interval(48, 54)
        self.assertLess(lower, 48 / 54)
        self.assertGreater(upper, 48 / 54)
        self.assertGreaterEqual(lower, 0.0)
        self.assertLessEqual(upper, 1.0)

    def test_offline_metric_counts_exact_match(self) -> None:
        """意图与三个槽位全部相同时才计入精确匹配。"""

        row = {
            "gold_dialogue_act": "PROPOSE_JOB",
            "gold_job_type": "COLLECT_BLOCK",
            "gold_target": "minecraft:oak_log",
            "gold_quantity": 8,
            "gold_radius": 16,
            "should_clarify": False,
            "should_reject": False,
            "valid_json": True,
            "predicted_dialogue_act": "PROPOSE_JOB",
            "predicted_job": {
                "type": "COLLECT_BLOCK",
                "target": "minecraft:oak_log",
                "quantity": 8,
                "radius": 16,
            },
        }
        metrics = evaluate_offline([row])
        self.assertEqual(1.0, metrics["intent_accuracy"])
        self.assertEqual(1.0, metrics["exact_match_accuracy"])


if __name__ == "__main__":
    unittest.main()
