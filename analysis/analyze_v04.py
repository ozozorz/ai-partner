#!/usr/bin/env python3
"""分析 AI Partner v0.4 的离线指令评测与游戏内批次结果。

脚本只读取冻结实验日志和仓库内数据集，不修改原始结果；生成的 CSV、JSON 与
Markdown 报告可直接用于论文结果审计。Rule-BT 离线结果由本脚本按当前 Java
解析器逐条复刻，正式归档前仍应使用游戏内 ``export-evaluation`` 命令交叉验证。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
import subprocess
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence


RULE_BT = "RULE_BT"
LLM_SCHEMA = "LLM_SCHEMA"
MAID_IBC = "MAID_IBC"
A2_VARIANT = "MAID_IBC_A2_NO_RUNTIME_MONITORING"

ARABIC_NUMBER = re.compile(r"(?<![\d.])(\d{1,2})(?![\d.])")
ARABIC_QUANTITY = re.compile(r"(?<![\d.])(\d{1,2})\s*(?:个|根|块)")
RADIUS_WITH_BLOCK_UNIT = re.compile(r"(?<![\d.])(\d{1,2})\s*格(?:范围)?(?:内|以内)?")
RADIUS_AFTER_LABEL = re.compile(r"(?:半径|范围)\s*(?:是|为)?\s*(\d{1,2})(?![\d.])")
CHINESE_NUMBERS = (
    "零",
    "一",
    "二",
    "三",
    "四",
    "五",
    "六",
    "七",
    "八",
    "九",
    "十",
    "十一",
    "十二",
    "十三",
    "十四",
    "十五",
    "十六",
    "十七",
    "十八",
    "十九",
    "二十",
)


def parse_args() -> argparse.Namespace:
    """解析仓库、日志、输出目录和可选 A2 批次参数。"""

    script_path = Path(__file__).resolve()
    repository = script_path.parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=repository)
    parser.add_argument(
        "--logs-root",
        type=Path,
        default=repository / "run" / "logs" / "ai-partner",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=repository / "analysis" / "generated",
    )
    parser.add_argument(
        "--main-batch",
        default="main-v04-162",
        help="作为主要游戏内结果的数据批次 ID。",
    )
    parser.add_argument(
        "--offline-run",
        default="offline-v04-main",
        help="作为主要离线模型结果的运行 ID。",
    )
    parser.add_argument(
        "--a2-batch",
        default=None,
        help="可选 A2 批次 ID；省略时自动发现唯一已完成批次。",
    )
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    """读取一个 UTF-8 JSON 对象。"""

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    """读取 JSONL，忽略纯空行并保留原有记录顺序。"""

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_number} 不是有效 JSON") from error
    return rows


def write_json(path: Path, value: Any) -> None:
    """以稳定、可审阅的格式写出 JSON。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=False)
        handle.write("\n")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    """以 UTF-8 JSONL 写出逐条结果。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def write_csv(path: Path, rows: Sequence[dict[str, Any]], fieldnames: Sequence[str]) -> None:
    """写出带 UTF-8 BOM 的 CSV，方便 Excel 直接打开中文字段。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def sha256_file(path: Path) -> str:
    """流式计算文件 SHA-256。"""

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_commit(repository: Path) -> str:
    """读取当前 Git 提交；失败时返回明确的 unknown。"""

    try:
        completed = subprocess.run(
            ["git", "-c", f"safe.directory={repository.as_posix()}", "rev-parse", "HEAD"],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    return completed.stdout.strip()


def ratio(numerator: int | float, denominator: int | float) -> float:
    """安全计算比例，空分母返回 0。"""

    return 0.0 if denominator == 0 else numerator / denominator


def wilson_interval(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float]:
    """计算二项比例的双侧 Wilson 95% 置信区间。"""

    if total <= 0:
        return 0.0, 0.0
    estimate = successes / total
    denominator = 1.0 + z * z / total
    centre = estimate + z * z / (2.0 * total)
    margin = z * math.sqrt(estimate * (1.0 - estimate) / total + z * z / (4.0 * total * total))
    return (centre - margin) / denominator, (centre + margin) / denominator


def percentile(values: Sequence[float], probability: float) -> float:
    """使用线性插值计算小样本百分位数。"""

    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def binomial_two_sided(successes: int, failures: int) -> float:
    """计算 p=0.5 下的精确双侧符号检验概率。"""

    total = successes + failures
    if total == 0:
        return 1.0
    tail = min(successes, failures)
    cumulative = sum(math.comb(total, index) for index in range(tail + 1)) / (2**total)
    return min(1.0, 2.0 * cumulative)


def contains_any(value: str, candidates: Sequence[str]) -> bool:
    """复刻 Java RuleJobParser 的子串触发规则。"""

    return any(candidate in value for candidate in candidates)


def parse_log_target(message: str) -> str | None:
    """从中英文原木名称解析服务器白名单目标。"""

    if contains_any(message, ("橡木", "oak")):
        return "minecraft:oak_log"
    if contains_any(message, ("白桦", "birch")):
        return "minecraft:birch_log"
    if contains_any(message, ("云杉", "spruce")):
        return "minecraft:spruce_log"
    return None


def parse_quantity(message: str) -> int:
    """按 Java 基线的优先级解析阿拉伯或一至二十中文数量。"""

    explicit = ARABIC_QUANTITY.search(message)
    if explicit:
        return int(explicit.group(1))
    any_number = ARABIC_NUMBER.search(message)
    if any_number:
        return int(any_number.group(1))
    for value in range(len(CHINESE_NUMBERS) - 1, 0, -1):
        if CHINESE_NUMBERS[value] in message:
            return value
    return 0


def parse_radius(message: str, default_radius: int = 16) -> int:
    """解析显式半径；未给出时复刻服务器默认值 16。"""

    with_unit = RADIUS_WITH_BLOCK_UNIT.search(message)
    if with_unit:
        return int(with_unit.group(1))
    after_label = RADIUS_AFTER_LABEL.search(message)
    return int(after_label.group(1)) if after_label else default_radius


def rule_bt_predict(instruction: str) -> tuple[str, dict[str, Any] | None]:
    """逐分支复刻 v0.4 Java RuleJobParser 的可观察预测。"""

    message = instruction.strip().lower()
    basic_rules = (
        (("取消", "停下", "停止任务", "cancel", "stop task"), "CANCEL"),
        (("待在这里", "原地待命", "别动", "stay", "wait here"), "STAY"),
        (("跟着我", "跟随我", "跟我来", "follow", "come with me"), "FOLLOW"),
    )
    for candidates, job_type in basic_rules:
        if contains_any(message, candidates):
            return "PROPOSE_JOB", {"type": job_type, "target": None, "quantity": None, "radius": None}

    collect_terms = ("收集", "砍", "collect", "chop", "get me")
    deposit_terms = ("放进箱子", "存进箱子", "存入箱子", "deposit", "put in the chest")
    requests_collection = contains_any(message, collect_terms)
    requests_deposit = contains_any(message, deposit_terms)
    if requests_collection and requests_deposit:
        job_type = "COLLECT_AND_DEPOSIT"
    elif requests_deposit:
        job_type = "DEPOSIT_ITEM"
    elif requests_collection:
        job_type = "COLLECT_BLOCK"
    else:
        return "ASK_CLARIFICATION", None

    target = parse_log_target(message)
    quantity = parse_quantity(message)
    if target is None or quantity <= 0:
        return "ASK_CLARIFICATION", None
    return "PROPOSE_JOB", {
        "type": job_type,
        "target": target,
        "quantity": quantity,
        "radius": parse_radius(message),
    }


def canonical_model_prediction(row: dict[str, Any]) -> dict[str, Any]:
    """把 Java 输出的驼峰字段转换为统一分析字段。"""

    predicted_job = row.get("predictedJob")
    return {
        "id": row["id"],
        "split": row["split"],
        "category": row["category"],
        "instruction": row["instruction"],
        "gold_dialogue_act": row["goldDialogueAct"],
        "gold_job_type": row.get("goldJobType"),
        "gold_target": row.get("goldTarget"),
        "gold_quantity": row.get("goldQuantity"),
        "gold_radius": row.get("goldRadius"),
        "should_clarify": bool(row.get("shouldClarify")),
        "should_reject": bool(row.get("shouldReject")),
        "valid_json": bool(row.get("validJson")),
        "predicted_dialogue_act": row.get("predictedDialogueAct"),
        "predicted_job": predicted_job,
        "latency_millis": row.get("latencyMillis", 0),
        "input_tokens": row.get("inputTokens", 0),
        "output_tokens": row.get("outputTokens", 0),
        "attempts": row.get("attempts", 0),
        "observed_cost_usd": row.get("observedEstimatedCostUsd", 0.0),
        "error_code": row.get("errorCode"),
    }


def canonical_rule_prediction(row: dict[str, Any]) -> dict[str, Any]:
    """从冻结金标生成统一格式的 Rule-BT 预测。"""

    predicted_act, predicted_job = rule_bt_predict(row["instruction"])
    return {
        "id": row["id"],
        "split": row["split"],
        "category": row["category"],
        "instruction": row["instruction"],
        "gold_dialogue_act": row["gold_dialogue_act"],
        "gold_job_type": row.get("gold_job_type"),
        "gold_target": row.get("gold_target"),
        "gold_quantity": row.get("gold_quantity"),
        "gold_radius": row.get("gold_radius"),
        "should_clarify": bool(row.get("should_clarify")),
        "should_reject": bool(row.get("should_reject")),
        "valid_json": True,
        "predicted_dialogue_act": predicted_act,
        "predicted_job": predicted_job,
        "latency_millis": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "attempts": 0,
        "observed_cost_usd": 0.0,
        "error_code": None,
    }


def normalize_slot(value: Any) -> Any:
    """把空字符串和非正数统一为缺失槽位。"""

    if value is None or value == "":
        return None
    if isinstance(value, (int, float)) and value <= 0:
        return None
    return value


def intent_matches(row: dict[str, Any]) -> bool:
    """按预注册定义判断对话行为与任务类型是否同时正确。"""

    if row.get("predicted_dialogue_act") != row["gold_dialogue_act"]:
        return False
    if row["gold_dialogue_act"] != "PROPOSE_JOB":
        return True
    job = row.get("predicted_job") or {}
    return job.get("type") == row.get("gold_job_type")


def slots_match(row: dict[str, Any]) -> bool:
    """判断可执行任务的三个槽位是否与金标完全相同。"""

    if row["gold_dialogue_act"] != "PROPOSE_JOB":
        return True
    job = row.get("predicted_job") or {}
    return all(
        normalize_slot(row.get(f"gold_{slot}")) == normalize_slot(job.get(slot))
        for slot in ("target", "quantity", "radius")
    )


def slot_metrics(rows: Sequence[dict[str, Any]], slot: str) -> dict[str, Any]:
    """按 Java 实现的微平均计数计算单个槽位 P/R/F1。"""

    true_positive = false_positive = false_negative = 0
    for row in rows:
        gold = normalize_slot(row.get(f"gold_{slot}"))
        predicted_job = row.get("predicted_job") if row.get("predicted_dialogue_act") == "PROPOSE_JOB" else None
        predicted = normalize_slot((predicted_job or {}).get(slot))
        if gold is None and predicted is None:
            continue
        if gold is not None and gold == predicted:
            true_positive += 1
            continue
        if predicted is not None:
            false_positive += 1
        if gold is not None:
            false_negative += 1
    precision = ratio(true_positive, true_positive + false_positive)
    recall = ratio(true_positive, true_positive + false_negative)
    f1 = ratio(2.0 * precision * recall, precision + recall)
    return {
        "true_positive": true_positive,
        "false_positive": false_positive,
        "false_negative": false_negative,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def evaluate_offline(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """计算一组离线预测的语义、槽位、延迟与成本指标。"""

    total = len(rows)
    valid = sum(bool(row.get("valid_json")) for row in rows)
    intent_correct = sum(intent_matches(row) for row in rows)
    exact = sum(intent_matches(row) and slots_match(row) for row in rows)
    clarify_gold = [row for row in rows if row.get("should_clarify")]
    reject_gold = [row for row in rows if row.get("should_reject")]
    executable_gold = [row for row in rows if row["gold_dialogue_act"] == "PROPOSE_JOB"]
    clarify_correct = sum(row.get("predicted_dialogue_act") == "ASK_CLARIFICATION" for row in clarify_gold)
    reject_correct = sum(row.get("predicted_dialogue_act") == "REJECT_UNSUPPORTED" for row in reject_gold)
    false_rejects = sum(row.get("predicted_dialogue_act") == "REJECT_UNSUPPORTED" for row in executable_gold)
    slots = {slot: slot_metrics(rows, slot) for slot in ("target", "quantity", "radius")}
    slot_macro_f1 = statistics.fmean(metric["f1"] for metric in slots.values()) if slots else 0.0
    latencies = [float(row.get("latency_millis", 0)) for row in rows if row.get("latency_millis", 0) > 0]
    return {
        "n": total,
        "jvr": ratio(valid, total),
        "intent_accuracy": ratio(intent_correct, total),
        "slot_macro_f1": slot_macro_f1,
        "target_f1": slots["target"]["f1"],
        "quantity_f1": slots["quantity"]["f1"],
        "radius_f1": slots["radius"]["f1"],
        "ccr": ratio(clarify_correct, len(clarify_gold)),
        "urr": ratio(reject_correct, len(reject_gold)),
        "frr": ratio(false_rejects, len(executable_gold)),
        "exact_match_accuracy": ratio(exact, total),
        "mean_latency_millis": statistics.fmean(latencies) if latencies else 0.0,
        "median_latency_millis": statistics.median(latencies) if latencies else 0.0,
        "p95_latency_millis": percentile(latencies, 0.95),
        "input_tokens": sum(int(row.get("input_tokens", 0)) for row in rows),
        "output_tokens": sum(int(row.get("output_tokens", 0)) for row in rows),
        "observed_cost_usd": sum(float(row.get("observed_cost_usd", 0.0)) for row in rows),
        "slots": slots,
    }


def offline_metric_rows(
    system: str,
    predictions: Sequence[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """生成 overall/dev/test 与分类指标的扁平 CSV 行。"""

    split_rows: list[dict[str, Any]] = []
    category_rows: list[dict[str, Any]] = []
    for split in ("all", "dev", "test"):
        selected = list(predictions) if split == "all" else [row for row in predictions if row["split"] == split]
        split_rows.append({"system": system, "split": split, **evaluate_offline(selected)})
    categories = sorted({row["category"] for row in predictions})
    for split in ("all", "dev", "test"):
        for category in categories:
            selected = [
                row
                for row in predictions
                if (split == "all" or row["split"] == split) and row["category"] == category
            ]
            category_rows.append(
                {"system": system, "split": split, "category": category, **evaluate_offline(selected)}
            )
    return split_rows, category_rows


def offline_error_rows(system: str, predictions: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """列出意图、槽位或 JSON 任一不满足金标的指令。"""

    errors: list[dict[str, Any]] = []
    for row in predictions:
        intent_ok = intent_matches(row)
        slots_ok = slots_match(row)
        if row.get("valid_json") and intent_ok and slots_ok:
            continue
        job = row.get("predicted_job") or {}
        errors.append(
            {
                "system": system,
                "id": row["id"],
                "split": row["split"],
                "category": row["category"],
                "instruction": row["instruction"],
                "valid_json": row.get("valid_json"),
                "gold_dialogue_act": row["gold_dialogue_act"],
                "predicted_dialogue_act": row.get("predicted_dialogue_act"),
                "gold_job_type": row.get("gold_job_type"),
                "predicted_job_type": job.get("type"),
                "gold_target": row.get("gold_target"),
                "predicted_target": normalize_slot(job.get("target")),
                "gold_quantity": row.get("gold_quantity"),
                "predicted_quantity": normalize_slot(job.get("quantity")),
                "gold_radius": row.get("gold_radius"),
                "predicted_radius": normalize_slot(job.get("radius")),
                "intent_correct": intent_ok,
                "slots_correct": slots_ok,
                "error_code": row.get("error_code"),
            }
        )
    return errors


def discover_a2_rows(logs_root: Path, explicit_batch: str | None) -> tuple[list[dict[str, Any]], str | None]:
    """读取明确指定或唯一可判定的已完成 A2 批次。"""

    batches_root = logs_root / "batches"
    if explicit_batch:
        result_path = batches_root / explicit_batch / "results.jsonl"
        if not result_path.is_file():
            raise FileNotFoundError(f"找不到 A2 批次结果：{result_path}")
        rows = [row for row in read_jsonl(result_path) if row.get("systemVariant") == A2_VARIANT]
        return rows, explicit_batch

    candidates: list[tuple[str, list[dict[str, Any]]]] = []
    if not batches_root.is_dir():
        return [], None
    for summary_path in batches_root.glob("*/summary.json"):
        summary = read_json(summary_path)
        variant_summary = summary.get("variants", {})
        if summary.get("status") != "COMPLETED" or A2_VARIANT not in variant_summary:
            continue
        result_path = summary_path.parent / "results.jsonl"
        if result_path.is_file():
            rows = [row for row in read_jsonl(result_path) if row.get("systemVariant") == A2_VARIANT]
            candidates.append((summary_path.parent.name, rows))
    if len(candidates) > 1:
        names = ", ".join(name for name, _ in candidates)
        raise ValueError(f"发现多个 A2 批次（{names}），请使用 --a2-batch 明确选择")
    if not candidates:
        return [], None
    return candidates[0][1], candidates[0][0]


def game_variant_metrics(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """按系统条件汇总场景判定、安全、一致性和运行时长。"""

    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[row["systemVariant"]].append(row)
    output: list[dict[str, Any]] = []
    preferred_order = (RULE_BT, LLM_SCHEMA, MAID_IBC, A2_VARIANT)
    for variant in sorted(groups, key=lambda item: preferred_order.index(item) if item in preferred_order else 99):
        selected = groups[variant]
        total = len(selected)
        passed = sum(bool(row.get("passed")) for row in selected)
        safety = sum(bool(row.get("safetySatisfied")) for row in selected)
        consistent = sum(bool(row.get("ibcConsistent")) for row in selected)
        excluded = sum(bool(row.get("excluded")) for row in selected)
        lower, upper = wilson_interval(passed, total)
        durations = [float(row.get("durationTicks", 0)) for row in selected]
        disturbances = [row for row in selected if row.get("disturbanceApplied")]
        recovery_attempts = sum(int(row.get("runtimeRecoveries", 0)) > 0 for row in disturbances)
        recovered_disturbances = sum(
            bool(row.get("passed")) and int(row.get("runtimeRecoveries", 0)) > 0
            for row in disturbances
        )
        recoverable = [row for row in selected if row.get("scenarioId") == "recoverable_target_change"]
        output.append(
            {
                "system_variant": variant,
                "episodes": total,
                "passed": passed,
                "failed": total - passed - excluded,
                "excluded": excluded,
                "pass_rate": ratio(passed, total),
                "pass_rate_ci95_low": lower,
                "pass_rate_ci95_high": upper,
                "safety_rate": ratio(safety, total),
                "ibcr": ratio(consistent, total),
                "mean_duration_ticks": statistics.fmean(durations) if durations else 0.0,
                "median_duration_ticks": statistics.median(durations) if durations else 0.0,
                "p95_duration_ticks": percentile(durations, 0.95),
                "disturbance_episodes": len(disturbances),
                "episodes_with_runtime_recovery_attempt": recovery_attempts,
                "recovered_disturbances": recovered_disturbances,
                "recoverable_target_change_passed": sum(bool(row.get("passed")) for row in recoverable),
                "recoverable_target_change_total": len(recoverable),
            }
        )
    return output


def game_failure_rows(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """输出所有未排除且场景终态不匹配的 episode。"""

    return [
        {
            "system_variant": row["systemVariant"],
            "scenario_id": row["scenarioId"],
            "repetition": row["repetition"],
            "expected_outcome": row["expectedOutcome"],
            "actual_outcome": row["actualOutcome"],
            "goal_satisfied": row.get("goalSatisfied"),
            "safety_satisfied": row.get("safetySatisfied"),
            "ibc_consistent": row.get("ibcConsistent"),
            "runtime_recoveries": row.get("runtimeRecoveries", 0),
            "duration_ticks": row.get("durationTicks", 0),
            "episode_id": row.get("episodeId"),
        }
        for row in rows
        if not row.get("passed") and not row.get("excluded")
    ]


def paired_comparisons(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """按场景×重复号配对，并同时给出场景聚类后的符号检验。"""

    by_variant: dict[str, dict[tuple[str, int], dict[str, Any]]] = defaultdict(dict)
    for row in rows:
        key = (row["scenarioId"], int(row["repetition"]))
        by_variant[row["systemVariant"]][key] = row
    pairs = ((MAID_IBC, LLM_SCHEMA), (MAID_IBC, RULE_BT), (MAID_IBC, A2_VARIANT))
    output: list[dict[str, Any]] = []
    for left, right in pairs:
        if left not in by_variant or right not in by_variant:
            continue
        shared = sorted(set(by_variant[left]) & set(by_variant[right]))
        left_only = right_only = ties_pass = ties_fail = 0
        scenario_values: dict[str, list[tuple[int, int]]] = defaultdict(list)
        for key in shared:
            left_pass = int(bool(by_variant[left][key].get("passed")))
            right_pass = int(bool(by_variant[right][key].get("passed")))
            scenario_values[key[0]].append((left_pass, right_pass))
            if left_pass and not right_pass:
                left_only += 1
            elif right_pass and not left_pass:
                right_only += 1
            elif left_pass:
                ties_pass += 1
            else:
                ties_fail += 1
        scenario_positive = scenario_negative = scenario_ties = 0
        for values in scenario_values.values():
            difference = statistics.fmean(left_value - right_value for left_value, right_value in values)
            if difference > 0:
                scenario_positive += 1
            elif difference < 0:
                scenario_negative += 1
            else:
                scenario_ties += 1
        output.append(
            {
                "left_variant": left,
                "right_variant": right,
                "paired_episodes": len(shared),
                "left_only_passed": left_only,
                "right_only_passed": right_only,
                "episode_exact_mcnemar_p": binomial_two_sided(left_only, right_only),
                "both_passed": ties_pass,
                "both_failed": ties_fail,
                "paired_scenarios": len(scenario_values),
                "scenario_positive": scenario_positive,
                "scenario_negative": scenario_negative,
                "scenario_ties": scenario_ties,
                "scenario_sign_test_p": binomial_two_sided(scenario_positive, scenario_negative),
            }
        )
    return output


def format_percent(value: float) -> str:
    """把 0–1 比例格式化为一位小数百分比。"""

    return f"{value * 100:.1f}%"


def render_markdown_report(
    metadata: dict[str, Any],
    game_metrics: Sequence[dict[str, Any]],
    failures: Sequence[dict[str, Any]],
    comparisons: Sequence[dict[str, Any]],
    offline_splits: Sequence[dict[str, Any]],
    offline_errors: Sequence[dict[str, Any]],
    radius_default_mismatches: Sequence[dict[str, Any]],
) -> str:
    """生成可直接审阅和引用的中文分析报告。"""

    a2_loaded = bool(metadata.get("a2_batch"))
    a2_comparison = next(
        (
            item
            for item in comparisons
            if item["left_variant"] == MAID_IBC and item["right_variant"] == A2_VARIANT
        ),
        None,
    )
    if a2_loaded and a2_comparison is not None:
        a2_integrity = (
            f"已载入 `{metadata['a2_batch']}`，{metadata['a2_status']}，"
            f"{metadata['a2_completed']}/{metadata['a2_planned']} episodes"
        )
        a2_audit = (
            "4. **A2 已完成。** Maid-IBC 与 A2 的配对结果为 "
            f"{a2_comparison['left_only_passed']}/{a2_comparison['right_only_passed']} 个 episode 单侧胜出，"
            f"场景级正/负/平为 {a2_comparison['scenario_positive']}/"
            f"{a2_comparison['scenario_negative']}/{a2_comparison['scenario_ties']}。"
            "该消融可以定位运行时监控与恢复在冻结场景内的贡献，但独立场景数仍限制因果外推。"
        )
    else:
        a2_integrity = "尚未运行"
        a2_audit = (
            "4. **A2 是因果归因所必需的消融。** 在 A2 完成前，不能把 Maid-IBC 与 "
            "LLM-Schema 的全部差异单独归因于运行时监控。"
        )
    next_steps = [
        "- 用 `/maid experiment export-evaluation` 导出并核对权威 Rule-BT 离线基线；",
        "- 主文报告 test 指标、场景聚类比较、效应量和失败案例，不把重复 episode 当作完全独立样本；",
        "- 主实验冻结后如修改 Prompt 默认半径，只能另建版本并标记为后续稳健性实验，不能替换 v0.4 原始结果。",
    ]
    if not a2_loaded:
        next_steps.insert(
            0,
            "- 在同一冻结指纹下完成 `MAID_IBC_A2_NO_RUNTIME_MONITORING` 的 18 场景 × 3 重复；",
        )

    lines = [
        "# AI Partner v0.4 实验分析报告",
        "",
        f"> 生成时间：{metadata['generated_at']}  ",
        f"> Git 提交：`{metadata['git_commit']}`  ",
        f"> 协议指纹：`{metadata['protocol_fingerprint']}`",
        "",
        "## 1. 数据完整性",
        "",
        f"- 主实验批次 `{metadata['main_batch']}`：{metadata['main_status']}，"
        f"{metadata['main_completed']}/{metadata['main_planned']} episodes；",
        f"- 离线模型运行 `{metadata['offline_run']}`：{metadata['offline_completed']}/"
        f"{metadata['offline_planned']} cases；",
        f"- A2 消融：{a2_integrity}；",
        f"- 预实验、冻结文件、主实验、离线模型运行{'、A2' if a2_loaded else ''} 的协议指纹一致。",
        "",
        f"## 2. 游戏内主实验{'与 A2 消融' if a2_loaded else ''}",
        "",
        "这里的“通过”表示实际终态与场景预注册期望一致，不等同于所有任务都以 `COMPLETED` 结束。",
        "",
        "| 系统 | n | 场景通过率（Wilson 95% CI） | 安全率 | IBCR | 可恢复目标变化 | 平均 tick |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for metric in game_metrics:
        ci = f"{format_percent(metric['pass_rate'])} [{format_percent(metric['pass_rate_ci95_low'])}, {format_percent(metric['pass_rate_ci95_high'])}]"
        recovery = f"{metric['recoverable_target_change_passed']}/{metric['recoverable_target_change_total']}"
        lines.append(
            f"| {metric['system_variant']} | {metric['episodes']} | {ci} | "
            f"{format_percent(metric['safety_rate'])} | {metric['ibcr']:.3f} | {recovery} | "
            f"{metric['mean_duration_ticks']:.1f} |"
        )

    lines.extend(["", "### 配对比较", ""])
    if comparisons:
        lines.extend(
            [
                "| 左系统 | 右系统 | episode 左胜/右胜 | episode 精确 p | 场景正/负/平 | 场景符号检验 p |",
                "|---|---|---:|---:|---:|---:|",
            ]
        )
        for item in comparisons:
            lines.append(
                f"| {item['left_variant']} | {item['right_variant']} | "
                f"{item['left_only_passed']}/{item['right_only_passed']} | "
                f"{item['episode_exact_mcnemar_p']:.4f} | "
                f"{item['scenario_positive']}/{item['scenario_negative']}/{item['scenario_ties']} | "
                f"{item['scenario_sign_test_p']:.4f} |"
            )
        lines.extend(
            [
                "",
                "> episode 级检验把同一场景的三次重复视为独立，可能高估证据；论文主文应优先报告场景聚类结果，episode 级结果仅作描述性补充。",
            ]
        )
    else:
        lines.append("没有可配对的系统结果。")

    lines.extend(["", "### 失败集中位置", ""])
    if failures:
        lines.extend(
            [
                "| 系统 | 场景 | 重复 | 期望 | 实际 |",
                "|---|---|---:|---|---|",
            ]
        )
        for row in failures:
            lines.append(
                f"| {row['system_variant']} | {row['scenario_id']} | {row['repetition']} | "
                f"{row['expected_outcome']} | {row['actual_outcome']} |"
            )
    else:
        lines.append("没有未排除的场景判定失败。")

    lines.extend(
        [
            "",
            "## 3. 离线指令评测",
            "",
            "Rule-BT 为 Python 对当前 Java 规则解析器的逐分支复刻；待游戏内导出后应核对 SHA-256 和逐条预测。论文最终指标应以 `test` 切分为主，`dev` 仅用于开发诊断。",
            "",
            "| 系统 | 切分 | n | JVR | 意图准确率 | 槽位宏 F1 | Radius F1 | CCR | URR | FRR | 精确匹配 |",
            "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for metric in offline_splits:
        lines.append(
            f"| {metric['system']} | {metric['split']} | {metric['n']} | "
            f"{format_percent(metric['jvr'])} | {format_percent(metric['intent_accuracy'])} | "
            f"{format_percent(metric['slot_macro_f1'])} | {format_percent(metric['radius_f1'])} | "
            f"{format_percent(metric['ccr'])} | {format_percent(metric['urr'])} | "
            f"{format_percent(metric['frr'])} | {format_percent(metric['exact_match_accuracy'])} |"
        )

    model_errors = [row for row in offline_errors if row["system"] == "DEEPSEEK_V4_FLASH"]
    lines.extend(
        [
            "",
            f"模型共有 {len(model_errors)} 条至少一项不匹配；完整列表见 `offline_errors.csv`。",
            "",
            "## 4. 测量审计与解释边界",
            "",
            f"1. **半径默认值不一致。** 有 {len(radius_default_mismatches)} 条指令的金标半径为 16、"
            "模型输出为 24，且文本没有显式半径。服务器默认值是 16，但冻结 Prompt 只声明合法范围 1–24，"
            "没有告诉模型省略半径时的默认值。因此低 Radius F1 同时反映协议信息缺口，不能全部解释为语义理解错误。",
            "2. **IBCR 天花板效应。** 当前各已载入系统 IBCR 均为 1.0，现有实验不能支持“IBC 相对基线提高 IBCR”的差异性结论；"
            "应把它报告为受控回复机制达成的一致性保证，并讨论指标区分度不足。",
            "3. **Rule-BT 与 Maid-IBC 主实验并列。** 两者均 54/54，IBC 的当前优势主要体现在相对 LLM-Schema 的运行时扰动处理，"
            "而不是相对规则系统的总体场景通过率。",
            a2_audit,
            "",
            "## 5. 后续验收与稳健性工作",
            "",
            *next_steps,
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    """执行完整审计并生成论文所需的结构化产物。"""

    args = parse_args()
    repository = args.repository.resolve()
    logs_root = args.logs_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    dataset_path = repository / "src" / "main" / "resources" / "assets" / "ai-partner" / "evaluation" / "offline_instructions_v1.jsonl"
    main_directory = logs_root / "batches" / args.main_batch
    offline_directory = logs_root / "evaluation" / "model-runs" / args.offline_run
    frozen_path = logs_root / "frozen-v0.4.json"

    required = (
        dataset_path,
        main_directory / "summary.json",
        main_directory / "results.jsonl",
        offline_directory / "manifest.json",
        offline_directory / "checkpoint.json",
        offline_directory / "predictions.jsonl",
        frozen_path,
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError("缺少必要实验文件：\n" + "\n".join(missing))

    dataset = read_jsonl(dataset_path)
    raw_model_predictions = read_jsonl(offline_directory / "predictions.jsonl")
    model_predictions = [canonical_model_prediction(row) for row in raw_model_predictions]
    rule_predictions = [canonical_rule_prediction(row) for row in dataset]
    if {row["id"] for row in model_predictions} != {row["id"] for row in rule_predictions}:
        raise ValueError("模型预测与冻结数据集的 case ID 不一致")

    main_summary = read_json(main_directory / "summary.json")
    main_rows = read_jsonl(main_directory / "results.jsonl")
    a2_rows, a2_batch = discover_a2_rows(logs_root, args.a2_batch)
    a2_summary = read_json(logs_root / "batches" / a2_batch / "summary.json") if a2_batch else None
    game_rows = [*main_rows, *a2_rows]
    offline_manifest = read_json(offline_directory / "manifest.json")
    offline_checkpoint = read_json(offline_directory / "checkpoint.json")
    frozen = read_json(frozen_path)

    protocol_fingerprint = frozen["snapshot"]["fingerprint"]
    observed_fingerprints = {
        main_summary.get("protocolFingerprint"),
        offline_manifest.get("protocolFingerprint"),
        protocol_fingerprint,
    }
    if a2_summary is not None:
        observed_fingerprints.add(a2_summary.get("protocolFingerprint"))
    if len(observed_fingerprints) != 1:
        raise ValueError(f"冻结、主实验与离线运行的协议指纹不一致：{observed_fingerprints}")
    if main_summary.get("status") != "COMPLETED" or offline_checkpoint.get("status") != "COMPLETED":
        raise ValueError("主实验或离线运行尚未完成，拒绝生成最终分析")
    if len(main_rows) != int(main_summary["completedEpisodes"]):
        raise ValueError("主实验 summary 与 results.jsonl 行数不一致")
    if a2_summary is not None:
        a2_variant = a2_summary.get("variants", {}).get(A2_VARIANT, {})
        if (
            a2_summary.get("batchKind") != "ABLATION_A2"
            or a2_summary.get("status") != "COMPLETED"
            or a2_summary.get("completedEpisodes") != 54
            or a2_summary.get("plannedEpisodes") != 54
            or a2_variant.get("total") != 54
            or len(a2_rows) != 54
        ):
            raise ValueError("A2 不是已完成且结果完整的 54-episode 冻结消融批次")

    game_metrics = game_variant_metrics(game_rows)
    failures = game_failure_rows(game_rows)
    comparisons = paired_comparisons(game_rows)
    model_split_metrics, model_category_metrics = offline_metric_rows("DEEPSEEK_V4_FLASH", model_predictions)
    rule_split_metrics, rule_category_metrics = offline_metric_rows("RULE_BT_REPLICA", rule_predictions)
    offline_splits = [*model_split_metrics, *rule_split_metrics]
    offline_categories = [*model_category_metrics, *rule_category_metrics]
    offline_errors = [
        *offline_error_rows("DEEPSEEK_V4_FLASH", model_predictions),
        *offline_error_rows("RULE_BT_REPLICA", rule_predictions),
    ]
    radius_default_mismatches = [
        row
        for row in model_predictions
        if row.get("gold_radius") == 16
        and normalize_slot((row.get("predicted_job") or {}).get("radius")) == 24
        and not RADIUS_WITH_BLOCK_UNIT.search(row["instruction"])
        and not RADIUS_AFTER_LABEL.search(row["instruction"])
    ]

    generated_at = datetime.now(timezone.utc).isoformat()
    metadata = {
        "schema_version": "0.4-analysis-2",
        "generated_at": generated_at,
        "git_commit": git_commit(repository),
        "protocol_fingerprint": protocol_fingerprint,
        "dataset_sha256_file_bytes": sha256_file(dataset_path),
        "dataset_sha256_protocol": offline_manifest.get("datasetSha256"),
        "main_batch": args.main_batch,
        "main_status": main_summary["status"],
        "main_planned": main_summary["plannedEpisodes"],
        "main_completed": main_summary["completedEpisodes"],
        "offline_run": args.offline_run,
        "offline_planned": len(offline_manifest["caseIds"]),
        "offline_completed": offline_checkpoint["processedCases"],
        "a2_batch": a2_batch,
        "a2_status": a2_summary.get("status") if a2_summary else None,
        "a2_planned": a2_summary.get("plannedEpisodes") if a2_summary else 0,
        "a2_completed": a2_summary.get("completedEpisodes") if a2_summary else 0,
        "rule_bt_replica_source_sha256": sha256_file(
            repository / "src" / "main" / "java" / "io" / "github" / "ozozorz" / "aipartner" / "parser" / "RuleJobParser.java"
        ),
        "radius_default_mismatch_count": len(radius_default_mismatches),
    }
    summary = {
        "metadata": metadata,
        "game_variant_metrics": game_metrics,
        "paired_comparisons": comparisons,
        "offline_split_metrics": offline_splits,
        "radius_default_mismatches": [row["id"] for row in radius_default_mismatches],
        "caveats": [
            "Rule-BT 离线预测是对 Java 解析器的 Python 复刻，需用游戏内导出交叉验证。",
            "episode 级重复存在场景内聚类，主推断应以场景为单位。",
            "冻结 Prompt 未声明缺省半径 16，radius 指标存在协议信息不对称。",
            (
                "A2 与完整系统的差异只来自有限独立场景，运行时监控的因果结论不能外推到开放任务。"
                if a2_batch
                else "A2 未载入时不能完成运行时监控的消融归因。"
            ),
        ],
    }

    game_fields = list(game_metrics[0].keys()) if game_metrics else []
    failure_fields = list(failures[0].keys()) if failures else [
        "system_variant",
        "scenario_id",
        "repetition",
        "expected_outcome",
        "actual_outcome",
        "goal_satisfied",
        "safety_satisfied",
        "ibc_consistent",
        "runtime_recoveries",
        "duration_ticks",
        "episode_id",
    ]
    comparison_fields = list(comparisons[0].keys()) if comparisons else []
    offline_fields = [
        "system",
        "split",
        "n",
        "jvr",
        "intent_accuracy",
        "slot_macro_f1",
        "target_f1",
        "quantity_f1",
        "radius_f1",
        "ccr",
        "urr",
        "frr",
        "exact_match_accuracy",
        "mean_latency_millis",
        "median_latency_millis",
        "p95_latency_millis",
        "input_tokens",
        "output_tokens",
        "observed_cost_usd",
    ]
    category_fields = ["system", "split", "category", *offline_fields[2:]]
    error_fields = list(offline_errors[0].keys()) if offline_errors else []

    write_json(output / "analysis_summary.json", summary)
    write_jsonl(output / "rule_bt_predictions_replica.jsonl", rule_predictions)
    write_csv(output / "game_variant_metrics.csv", game_metrics, game_fields)
    write_csv(output / "game_failures.csv", failures, failure_fields)
    write_csv(output / "paired_comparisons.csv", comparisons, comparison_fields)
    write_csv(output / "offline_split_metrics.csv", offline_splits, offline_fields)
    write_csv(output / "offline_category_metrics.csv", offline_categories, category_fields)
    write_csv(output / "offline_errors.csv", offline_errors, error_fields)
    report = render_markdown_report(
        metadata,
        game_metrics,
        failures,
        comparisons,
        offline_splits,
        offline_errors,
        radius_default_mismatches,
    )
    (output / "analysis_report.md").write_text(report, encoding="utf-8", newline="\n")
    print(f"分析完成：{output}")
    print(f"主实验 episodes={len(main_rows)}，A2 episodes={len(a2_rows)}，离线 cases={len(model_predictions)}")


if __name__ == "__main__":
    main()
