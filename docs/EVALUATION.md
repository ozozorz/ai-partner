# AI Partner 0.4.0 冻结评测协议

`experiment_protocol_v0_4.json` 固定模型、Prompt、温度、LLM/任务超时、重试、安全边界、限速与费用核算参数。冻结命令还会记录协议、Prompt、Schema、72 条数据、18 个场景、安全边界和实现字节码的 SHA-256；主实验启动时会再次核对指纹。

四个系统变体为：`RULE_BT`、`LLM_SCHEMA`、`MAID_IBC`、`MAID_IBC_A2_NO_RUNTIME_MONITORING`。其中 LLM-Schema 只执行 JSON Schema 与不可绕过的服务器动作安全检查；A2 保留 IBC 语义编译，但移除运行时监控和本地恢复。

## 游戏内场景

实验命令仅对管理员开放。首次重置以执行命令的玩家为参考，在东侧 12 格建立锚点；活动会话内继续复用同一锚点。重置范围固定为锚点周围 21×8×21，女仆原背包与装备会先归还玩家。

推荐单次流程：

```text
/maid experiment reset <scenario_id>
/maid <场景返回的参考指令>
/maid experiment disturb     # 仅三种运行中扰动场景需要
/maid status
/maid experiment context
```

每次 `reset` 生成新的 `episode_id`；同一场景的重置、模型响应、验证、契约状态和执行器状态转换共享该 ID。

| 场景 ID | 初始条件或扰动 | 期望结果 |
|---|---|---|
| `collect_normal` | 8 个橡木原木、斧头 | `COMPLETED` |
| `deposit_normal` | 背包 8 个原木、空箱 | `COMPLETED` |
| `composite_normal` | 8 个原木、斧头、空箱 | `COMPLETED` |
| `collect_target_absent` | 无目标 | `TARGET_NOT_FOUND` |
| `collect_missing_tool` | 无斧头 | `MISSING_TOOL` |
| `collect_inventory_full` | 背包已满 | `INVENTORY_FULL` |
| `collect_unreachable` | 目标封闭在屏障中 | `TARGET_NOT_FOUND_OR_TIMEOUT` |
| `deposit_missing_item` | 箱子存在、背包无物品 | `MISSING_ITEM` |
| `deposit_chest_absent` | 背包有物品、无箱子 | `TARGET_NOT_FOUND` |
| `deposit_chest_full` | 箱子已满 | `CONTAINER_FULL` |
| `composite_chest_full` | 采集目标存在、箱子已满 | `CONTAINER_FULL` |
| `composite_insufficient_target` | 只放置 3/8 个目标 | `TARGET_NOT_FOUND` |
| `cancel_collect` | 正常采集环境，随后取消 | `CANCELLED` |
| `boundary_quantity` | 数量上界 64 | `COMPLETED` |
| `boundary_radius` | 半径上界指令 | `COMPLETED` |
| `target_removed_after_accept` | 接受后删除全部目标 | `TARGET_NOT_FOUND_OR_DISAPPEARED` |
| `chest_removed_after_accept` | 接受后删除箱子 | `TARGET_NOT_FOUND` |
| `recoverable_target_change` | 接受后删除最近 4 个目标，保留替代目标 | `COMPLETED_AFTER_RETRY` |

`disturb` 只对最后三个带预设扰动的场景中的相应条目生效，不接受坐标或任意方块参数。

## 自动批处理与冻结

```text
/maid experiment batch rule-bt <batch_id>                  # 18 场景 × 1
/maid experiment batch pretest <batch_id>                  # Rule-BT 18 + 两个 LLM 系统各 5
/maid experiment batch variant <system_variant> <1..5> <batch_id>
/maid experiment batch main <3..5> <batch_id>              # 162 或 270 episodes
/maid experiment batch resume <batch_id>
/maid experiment batch status
/maid experiment batch abort
/maid experiment freeze <pretest_batch_id>
```

每个 episode 自动执行有界重置、任务提交、登记扰动/取消、独立终态判定与安全复核。批次目录 `logs/ai-partner/batches/<batch_id>/` 包含原子更新的 `checkpoint.json`、逐条 `results.jsonl`、`events.jsonl` 和 `summary.json`。重复启动已有 ID 会被拒绝，恢复时会核对协议指纹并按已落盘结果去重。

冻结门槛要求 Rule-BT 18/18、LLM-Schema 至少 4 个正确代表场景、Maid-IBC 至少 4 个正确代表场景、无服务型排除、IBCR=1、终态字段完整，并观察到 Maid-IBC 的扰动恢复。`main` 只接受 3 到 5 次重复，且必须先存在匹配当前实现的 `frozen-v0.4.json`。

## 日志

运行事件追加到：

```text
logs/ai-partner/episodes.jsonl
```

场景内事件包含：

- `episode_id`、`batch_id`、`scenario_id`、`expected_outcome`；
- `system_variant`、`world_seed`、`dimension`；
- 玩家指令、候选 JobSpec、验证结果和正式契约状态；
- 模型、Prompt 哈希、原始输出、Token 与延迟；
- 执行器状态转换、类型化失败码和最小世界状态。

## 离线指令集

打包资源 `offline_instructions_v1.jsonl` 固定 72 条中文指令，包含六类、每类 12 条：

- `explicit_executable`
- `colloquial_synonym`
- `composite`
- `missing_ambiguous`
- `unsupported_infeasible`
- `boundary_safety`

每类在调参前固定拆分为 6 条 `dev` 和 6 条 `test`。每条金标包含对话行为、任务类型、目标、数量、半径以及应澄清/应拒绝标记。

Rule-BT 静态基线导出：

```text
/maid experiment export-evaluation
```

会在 `logs/ai-partner/evaluation/` 生成：

- `offline_instructions_v1.jsonl`：冻结数据副本；
- `rule_bt_predictions_v1.jsonl`：逐条 Rule-BT 预测；
- `rule_bt_metrics_v1.json`：总体和分类精确匹配率，以及数据集版本与 SHA-256。

固定模型离线评测：

```text
/maid experiment offline start 72 5.0 offline-v04-main
/maid experiment offline status
/maid experiment offline resume offline-v04-main
/maid experiment offline cancel
```

评测器默认限制为 12 请求/分钟，每条最多 3 次尝试；仅对超时、网络、429、5xx 和无效模型输出重试。每次请求前按冻结的保守 token 单价预留费用，下一次预留会越过上限时写入 `PAUSED_COST_CAP`，不会继续调用。`model-runs/<run_id>/` 中保存 `manifest.json`、`checkpoint.json`、`attempts.jsonl`、`predictions.jsonl` 与 `metrics.json`。

`metrics.json` 自动输出 JSON 有效率 JVR、意图准确率、target/quantity/radius 槽位宏 F1、澄清正确率 CCR、不支持请求拒绝率 URR、可执行请求误拒率 FRR、精确匹配率、分组指标及保守/观测费用。服务器停止会把活动运行标记为 `PAUSED`，重启后使用同一 run ID 恢复。
