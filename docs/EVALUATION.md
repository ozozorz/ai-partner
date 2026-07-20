# AI Partner 0.3.0 评测协议

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
| `target_removed_after_accept` | 接受后删除全部目标 | `TARGET_NOT_FOUND` |
| `chest_removed_after_accept` | 接受后删除箱子 | `TARGET_NOT_FOUND` |
| `recoverable_target_change` | 接受后删除最近 4 个目标，保留替代目标 | `COMPLETED_AFTER_RETRY` |

`disturb` 只对最后三个带预设扰动的场景中的相应条目生效，不接受坐标或任意方块参数。

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

执行：

```text
/maid experiment export-evaluation
```

会在 `logs/ai-partner/evaluation/` 生成：

- `offline_instructions_v1.jsonl`：冻结数据副本；
- `rule_bt_predictions_v1.jsonl`：逐条 Rule-BT 预测；
- `rule_bt_metrics_v1.json`：总体和分类精确匹配率，以及数据集版本与 SHA-256。

当前命令不会自动发起 72 次 DeepSeek 请求，避免未确认的 API 成本。模型批量评测留给带限速、断点恢复和成本上限的后续工具。
