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
| 实体与持久化 | `entity/AiPartnerEntity` | 主人、36 格背包、原生装备、唯一活动契约、同步行为模式 |
| 容器与 UI | `inventory/AiPartnerMenu`、`client/screen/AiPartnerScreen` | 服务端权威物品移动、状态同步和白名单行为按钮 |
| 客户端表现 | `client/render/AiPartnerRenderer` | 复用内置 `PLAYER_SLIM`、官方 Alex 贴图、护甲与手持物层 |
| 核心行为 | `entity/goal`、`entity/navigation` | 空闲漫步、观察、开关门和带滞回/延迟传送的主人跟随 |
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

## 生物行为分层

行为设计参考了 TouhouLittleMaid 的 `EntityMaid`、`MaidBrain` 和导航实现所采用的分层原则：持续可用的核心行为与任务专属行为分开，空闲阶段在观察、短距离漫步和静止之间切换，导航统一处理门与水面。当前 Fabric 实现没有复制其 Forge Brain 代码，而是用 26.1.2 原版 Goal/Navigation API 做了小型适配：

- 核心层：漂浮、开关木门、观察、空闲漫步；
- 契约层：跟随、待命、收集和存放；
- 跟随采用 5 格启动、3 格停止的滞回区间，优先寻路，持续卡住且远距离时才尝试传送；
- UI 打开期间暂停移动与任务超时，关闭后回到原契约状态。

## 白名单与安全规则

- 收集与存放只支持 `minecraft:oak_log`、`minecraft:birch_log` 和 `minecraft:spruce_log`。
- 数量范围为 1—64，搜索半径范围为 1—24。
- 收集需要女仆背包中有斧头，并受 `mobGriefing` 游戏规则约束。
- 存放只使用未阻挡、主人可打开且容量足够的普通单箱；不操作锁定箱子和双箱。
- 女仆没有攻击目标，也不会从模型接收坐标级动作或代码。
- 背包操作期间暂停导航和任务超时计时；关闭 UI 后，执行器会重新检查工具、物品和容量前置条件。
- UI 行为按钮只发送固定编号；主人身份、距离和契约前置条件仍由服务端验证。

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
- 尚未加入自动化客户端截图回归；视觉和交互仍需在开发客户端中进行实机验收。
