# AI Partner v0.4 实验分析报告

> 生成时间：2026-07-21T04:23:53.169501+00:00  
> Git 提交：`aaa6d9b0508e8315c092b9e34ee7bdb71a25765e`  
> 协议指纹：`1ac6f06b5f3388c451d235b28b14a8785cb9bed6e6255e344cdcb1258538e5fb`

## 1. 数据完整性

- 主实验批次 `main-v04-162`：COMPLETED，162/162 episodes；
- 离线模型运行 `offline-v04-main`：72/72 cases；
- A2 消融：已载入 `a2-v04-54`，COMPLETED，54/54 episodes；
- 预实验、冻结文件、主实验、离线模型运行、A2 的协议指纹一致。

## 2. 游戏内主实验与 A2 消融

这里的“通过”表示实际终态与场景预注册期望一致，不等同于所有任务都以 `COMPLETED` 结束。

| 系统 | n | 场景通过率（Wilson 95% CI） | 安全率 | IBCR | 可恢复目标变化 | 平均 tick |
|---|---:|---:|---:|---:|---:|---:|
| RULE_BT | 54 | 100.0% [93.4%, 100.0%] | 100.0% | 1.000 | 3/3 | 145.6 |
| LLM_SCHEMA | 54 | 88.9% [77.8%, 94.8%] | 100.0% | 1.000 | 0/3 | 176.6 |
| MAID_IBC | 54 | 100.0% [93.4%, 100.0%] | 100.0% | 1.000 | 3/3 | 179.5 |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | 54 | 88.9% [77.8%, 94.8%] | 100.0% | 1.000 | 0/3 | 172.4 |

### 配对比较

| 左系统 | 右系统 | episode 左胜/右胜 | episode 精确 p | 场景正/负/平 | 场景符号检验 p |
|---|---|---:|---:|---:|---:|
| MAID_IBC | LLM_SCHEMA | 6/0 | 0.0312 | 2/0/16 | 0.5000 |
| MAID_IBC | RULE_BT | 0/0 | 1.0000 | 0/0/18 | 1.0000 |
| MAID_IBC | MAID_IBC_A2_NO_RUNTIME_MONITORING | 6/0 | 0.0312 | 2/0/16 | 0.5000 |

> episode 级检验把同一场景的三次重复视为独立，可能高估证据；论文主文应优先报告场景聚类结果，episode 级结果仅作描述性补充。

### 失败集中位置

| 系统 | 场景 | 重复 | 期望 | 实际 |
|---|---|---:|---|---|
| LLM_SCHEMA | chest_removed_after_accept | 1 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| LLM_SCHEMA | recoverable_target_change | 1 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |
| LLM_SCHEMA | chest_removed_after_accept | 2 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| LLM_SCHEMA | recoverable_target_change | 2 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |
| LLM_SCHEMA | chest_removed_after_accept | 3 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| LLM_SCHEMA | recoverable_target_change | 3 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | chest_removed_after_accept | 1 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | recoverable_target_change | 1 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | chest_removed_after_accept | 2 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | recoverable_target_change | 2 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | chest_removed_after_accept | 3 | TARGET_NOT_FOUND | TARGET_DISAPPEARED |
| MAID_IBC_A2_NO_RUNTIME_MONITORING | recoverable_target_change | 3 | COMPLETED_AFTER_RETRY | TARGET_DISAPPEARED |

## 3. 离线指令评测

Rule-BT 为 Python 对当前 Java 规则解析器的逐分支复刻；待游戏内导出后应核对 SHA-256 和逐条预测。论文最终指标应以 `test` 切分为主，`dev` 仅用于开发诊断。

| 系统 | 切分 | n | JVR | 意图准确率 | 槽位宏 F1 | Radius F1 | CCR | URR | FRR | 精确匹配 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| DEEPSEEK_V4_FLASH | all | 72 | 98.6% | 86.1% | 66.7% | 8.0% | 57.1% | 93.8% | 0.0% | 43.1% |
| DEEPSEEK_V4_FLASH | dev | 36 | 100.0% | 83.3% | 64.8% | 11.4% | 42.9% | 100.0% | 0.0% | 47.2% |
| DEEPSEEK_V4_FLASH | test | 36 | 97.2% | 88.9% | 68.3% | 5.0% | 71.4% | 88.9% | 0.0% | 38.9% |
| RULE_BT_REPLICA | all | 72 | 100.0% | 61.1% | 80.6% | 80.6% | 92.9% | 0.0% | 0.0% | 61.1% |
| RULE_BT_REPLICA | dev | 36 | 100.0% | 75.0% | 90.3% | 90.3% | 100.0% | 0.0% | 0.0% | 75.0% |
| RULE_BT_REPLICA | test | 36 | 100.0% | 47.2% | 72.2% | 72.2% | 85.7% | 0.0% | 0.0% | 47.2% |

模型共有 41 条至少一项不匹配；完整列表见 `offline_errors.csv`。

## 4. 测量审计与解释边界

1. **半径默认值不一致。** 有 33 条指令的金标半径为 16、模型输出为 24，且文本没有显式半径。服务器默认值是 16，但冻结 Prompt 只声明合法范围 1–24，没有告诉模型省略半径时的默认值。因此低 Radius F1 同时反映协议信息缺口，不能全部解释为语义理解错误。
2. **IBCR 天花板效应。** 当前各已载入系统 IBCR 均为 1.0，现有实验不能支持“IBC 相对基线提高 IBCR”的差异性结论；应把它报告为受控回复机制达成的一致性保证，并讨论指标区分度不足。
3. **Rule-BT 与 Maid-IBC 主实验并列。** 两者均 54/54，IBC 的当前优势主要体现在相对 LLM-Schema 的运行时扰动处理，而不是相对规则系统的总体场景通过率。
4. **A2 已完成。** Maid-IBC 与 A2 的配对结果为 6/0 个 episode 单侧胜出，场景级正/负/平为 2/0/16。该消融可以定位运行时监控与恢复在冻结场景内的贡献，但独立场景数仍限制因果外推。

## 5. 后续验收与稳健性工作

- 用 `/maid experiment export-evaluation` 导出并核对权威 Rule-BT 离线基线；
- 主文报告 test 指标、场景聚类比较、效应量和失败案例，不把重复 episode 当作完全独立样本；
- 主实验冻结后如修改 Prompt 默认半径，只能另建版本并标记为后续稳健性实验，不能替换 v0.4 原始结果。
