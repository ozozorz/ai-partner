# AI Partner 架构说明

## 目标与边界

AI Partner 采用 IBC（Instruction–Behavior Contract，指令—行为契约）把自然语言解释与世界动作隔离。第一版只支持单玩家、单女仆和以下任务：

- `FOLLOW`
- `STAY`
- `COLLECT_BLOCK`
- `DEPOSIT_ITEM`
- `CANCEL`

`COLLECT_AND_DEPOSIT` 已保留在 DSL 和 TaskDefinition 注册表中，但执行能力明确标为未实现，候选任务会在执行前被拒绝。

## 数据流

```text
/maid <message>
  -> 本地 CANCEL 快速通道
  -> Rule-BT（LLM 关闭时）或异步 LLM 网关
  -> 严格 JobSpec JSON 校验
  -> TaskDefinition 注册表
  -> IBC 动态前置条件与安全验证
  -> 验证结果驱动的语言回复
  -> 唯一活动契约
  -> 确定性状态机
  -> 目标谓词/类型化终态
  -> JSONL 实验日志
```

## 主要模块

| 模块 | 入口 | 作用 |
|---|---|---|
| 实体与持久化 | `entity/AiPartnerEntity` | 主人、18 格背包、唯一活动契约、同步行为模式 |
| 客户端表现 | `client/render/AiPartnerRenderer` | 复用内置 `PLAYER_SLIM` 和官方 Alex 贴图 |
| 任务定义 | `contract/TaskDefinitionRegistry` | 冻结任务能力、参数边界和目标白名单 |
| 契约编译 | `contract/ContractCompiler` | 权限、维度、工具、目标、箱子和容量验证 |
| 收集执行器 | `executor/CollectBlockExecutor` | 有界搜索、寻路、检查、破坏、拾取和目标判定 |
| 存放执行器 | `executor/DepositItemExecutor` | 箱子搜索、权限/容量复检和精确物品转移 |
| 模型网关 | `llm/LlmGateway` | 异步 HTTP、超时、最多一次重试、Token/延迟采集 |
| 结构校验 | `llm/JobSpecJsonCodec` | 拒绝代码块、额外字段、错误类型和越界参数 |
| 实验日志 | `logging/ExperimentLogger` | 单线程异步 JSONL 和服务器停止时 flush |

## 线程模型

- Minecraft 世界读取、契约编译和任务调度只在服务器线程执行。
- HTTP 请求、响应读取和 JSON 初步解析在 `HttpClient` 异步链中执行。
- 模型结果通过 `MinecraftServer.execute` 回到服务器线程；玩家离线时不会调度任务。
- 日志写入使用独立单线程守护执行器；服务器停止事件会等待排队日志落盘。
- 同一玩家的新模型请求会取消旧请求；`CANCEL` 永远优先使用本地即时路径。

## 状态机

`COLLECT_BLOCK`：

```text
SEARCH_TARGET -> NAVIGATE -> CHECK_TARGET -> BREAK_BLOCK
              -> PICK_UP -> CHECK_GOAL -> SEARCH_TARGET / COMPLETE
```

`DEPOSIT_ITEM`：

```text
SEARCH_CONTAINER -> NAVIGATE -> CHECK_CONTAINER -> DEPOSIT
                 -> CHECK_GOAL -> SEARCH_CONTAINER / COMPLETE
```

每个执行器都有 90 秒默认超时、有限本地重试、原点半径监控和类型化失败码。收集搜索每 tick 最多检查 1024 个坐标，避免无界扫描。

## 白名单与安全规则

- 收集与存放只支持 `minecraft:oak_log`、`minecraft:birch_log` 和 `minecraft:spruce_log`。
- 数量范围为 1—64，搜索半径范围为 1—24。
- 收集需要女仆背包中有斧头，并受 `mobGriefing` 游戏规则约束。
- 存放只使用未阻挡、主人可打开且容量足够的普通单箱；不操作锁定箱子和双箱。
- 女仆没有攻击目标，也不会从模型接收坐标级动作或代码。
- 任务执行期间背包交互锁定，避免外部转移导致目标谓词被误判。

## 持久化与日志

实体存档保存主人、行为模式、背包、契约 ID/任务/状态/失败码、收集基线、存放进度和实验系统变体。运行时日志位于：

```text
logs/ai-partner/episodes.jsonl
```

日志事件包括模型结果、执行前验证、契约生命周期和执行器状态转换。模型密钥不会进入配置文件、Prompt 或日志。

## 当前限制

- 尚无固定世界快照和一键情景重置命令；需要在实验自动化里程碑补齐。
- 尚未实现 `COLLECT_AND_DEPOSIT` 的固定两阶段编排。
- 没有通用领地模组接口；第一版只依赖主人、箱锁、白名单、范围和 `mobGriefing` 约束。
- 未进行客户端实机视觉验收；当前验证范围是源码映射、编译、资源解析和纯 JVM 单元测试。

