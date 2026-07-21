# v0.4 实验分析

本目录提供不修改冻结原始数据的分析与归档工具。默认输入来自开发运行目录
`run/logs/ai-partner/`，输出位于 `analysis/generated/`。

## 运行

```powershell
python analysis\analyze_v04.py
python -m unittest discover -s analysis -p test_*.py -v
```

分析器会验证冻结文件、主实验和离线评测的协议指纹，随后生成：

- `analysis_report.md`：论文结果审计报告；
- `analysis_summary.json`：机器可读汇总；
- `game_variant_metrics.csv`：系统条件的场景通过、安全、一致性和耗时指标；
- `paired_comparisons.csv`：episode 级与场景聚类配对结果；
- `game_failures.csv`：实际终态与预注册期望不一致的 episode；
- `offline_split_metrics.csv`：离线模型和 Rule-BT 的 overall/dev/test 指标；
- `offline_category_metrics.csv`：按指令类别拆分的指标；
- `offline_errors.csv`：逐条错误分析；
- `rule_bt_predictions_replica.jsonl`：Python 对 Java 规则解析器的复刻预测。

Rule-BT 复刻结果必须与游戏内权威导出交叉验证：

```text
/maid experiment export-evaluation
```

## A2 消融

完成 A2 后，分析器会自动发现唯一一个已完成且包含
`MAID_IBC_A2_NO_RUNTIME_MONITORING` 的批次。若存在多个批次，应明确指定：

```powershell
python analysis\analyze_v04.py --a2-batch a2-v04-54
```

推荐的冻结实验命令为：

```text
/maid experiment batch variant MAID_IBC_A2_NO_RUNTIME_MONITORING 3 a2-v04-54
```

## 原始数据快照

```powershell
python analysis\snapshot_v04.py
python analysis\snapshot_v04.py --output artifacts\experiments\v0.4-with-a2 --a2-batch a2-v04-54
```

快照工具会生成逐文件 SHA-256、论文常用的结构化结果副本和完整日志 ZIP。已有快照默认
不会覆盖；只有在明确重建同一版本时才使用 `--force`。当前含 A2 的快照位于
`v0.4-with-a2`，原 `v0.4-primary` 保持不变。

## 统计解释

同一场景的三次重复不是完全独立样本。`paired_comparisons.csv` 同时给出 episode 级精确
McNemar 结果和按场景聚类后的符号检验；论文主文应优先使用后者，前者仅作为描述性补充。
