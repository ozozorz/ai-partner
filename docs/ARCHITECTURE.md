# AI Partner 架构说明

## 目标与边界

AI Partner 采用 IBC（Instruction–Behavior Contract，指令—行为契约）把自然语言解释与世界动作隔离。当前只支持单玩家、单女仆和以下任务：

- `FOLLOW`
- `STAY`
- `COLLECT_BLOCK`
- `DEPOSIT_ITEM`
- `COLLECT_AND_DEPOSIT`
- `CANCEL`

`COLLECT_AND_DEPOSIT` 是服务器固定的“先采集、后存箱”两阶段流程。模型只能提出目标、数量与半径，不能改变阶段顺序或插入坐标级动作。

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
| 组合编排器 | `executor/CollectAndDepositExecutor` | 复用两个单阶段状态机、保持一个父契约并控制阶段切换 |
| 模型网关 | `llm/LlmGateway` | 异步 HTTP、超时、最多一次重试、Token/延迟采集 |
| 结构校验 | `llm/JobSpecJsonCodec` | 拒绝代码块、额外字段、错误类型和越界参数 |
| 实验日志 | `logging/ExperimentLogger` | 单线程异步 JSONL 和服务器停止时 flush |
| 场景系统 | `experiment/*` | 18 个冻结场景、有界重置、预设扰动和 episode 上下文 |
| 离线评测 | `evaluation/*` | 72 条冻结中文金标、Rule-BT 逐条预测与分类指标导出 |

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

`COLLECT_AND_DEPOSIT`：

```text
COLLECTING（复用 COLLECT_BLOCK）
  -> 采集目标成立
  -> DEPOSITING（复用 DEPOSIT_ITEM）
  -> 箱子增量目标成立
  -> COMPLETE
```

组合任务只拥有一个父契约和一个 90 秒总预算。子执行器通过类型化监听器返回阶段结果，不会在采集完成时提前完成父契约；阶段、采集基线和已存数量都会写入实体存档。

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

实体存档保存主人、行为模式、背包、契约 ID/任务/状态/失败码、组合阶段、收集基线、存放进度和实验系统变体。运行时日志位于：

```text
logs/ai-partner/episodes.jsonl
```

日志事件包括场景重置/扰动、模型结果、执行前验证、契约生命周期和执行器状态转换。活动实验中的全部事件共享 `episode_id`，并附带批次、`scenario_id`、期望终态、世界种子和维度。模型密钥不会进入配置文件、Prompt 或日志。

管理员命令 `/maid experiment reset <scenario>` 只重建锚点周围 21×8×21 区域；`disturb` 只执行注册表声明的方块删除。`/maid experiment export-evaluation` 导出版本化数据、Rule-BT 预测和含数据集 SHA-256 的指标文件。

## 当前限制

- 场景已有确定性重置，但尚未接入 GameTest 自动逐 tick 驱动、重复运行和自动目标判定。
- 离线导出当前只自动运行 Rule-BT；模型批量评测仍需增加限速、断点恢复与成本保护。
- 没有通用领地模组接口；第一版只依赖主人、箱锁、白名单、范围和 `mobGriefing` 约束。
- 尚未加入自动化客户端截图回归；视觉和交互仍需在开发客户端中进行实机验收。
