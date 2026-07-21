# AI Partner v0.5 架构说明

## 目标与边界

v0.5 是一次不增加新玩法的纯架构重构。它保持 v0.4 的公开命令、任务语义、安全检查、实验变体和旧存档兼容，同时为后续生命周期、日程、战斗、农业、挖矿、熔炼和钓鱼建立稳定扩展点。

当前正式能力仍为：

- FOLLOW；
- STAY；
- COLLECT_BLOCK；
- DEPOSIT_ITEM；
- COLLECT_AND_DEPOSIT；
- CANCEL。

其中 FOLLOW 和 STAY 是长期手动指令，CANCEL 是订单服务操作；只有后三种工作由有限任务执行器驱动。

详细的长期功能规划见 [FOUNDATION_ARCHITECTURE_ROADMAP_ZH.md](./FOUNDATION_ARCHITECTURE_ROADMAP_ZH.md)。

## 依赖规则

核心依赖必须保持单向：

~~~text
命令 / GUI / 本地解析器 / LLM
              ↓
       MaidOrderService
              ↓
  ContractCompiler + 验证器注册表
              ↓
      MaidTaskRuntime
        ↙           ↘
行为控制器          任务注册表
                       ↓
                MaidTask 适配器
                       ↓
             可复用动作 + 旧执行器
                       ↓
            Minecraft 服务端世界
~~~

实验和日志位于旁路：

~~~text
核心运行时 → MaidDomainEvent → ExperimentEventBridge → ExperimentLogger
~~~

核心、实体、任务和 GUI 不允许导入 experiment、evaluation、llm 或 logging 包。

## 总体架构

~~~mermaid
flowchart TB
    subgraph Input["输入适配层"]
        Command["/maid 命令"]
        Menu["女仆 GUI"]
        Parser["规则解析器"]
        LLM["可选 LLM"]
    end

    Order["MaidOrderService"]
    Compiler["ContractCompiler<br/>通用验证"]
    Validators["TaskContractValidatorRegistry<br/>任务专属验证"]
    Runtime["MaidTaskRuntime<br/>唯一活动契约"]
    Behavior["MaidBehaviorController<br/>指令与显示模式"]
    Tasks["MaidTaskRegistry<br/>任务 ID 与工厂"]

    subgraph Gameplay["有限任务适配层"]
        Collect["CollectBlockMaidTask"]
        Deposit["DepositItemMaidTask"]
        Composite["CollectAndDepositMaidTask"]
    end

    Actions["MaidActions<br/>导航、破坏、物品转移"]
    Minecraft["实体、世界、导航、容器"]
    Events["MaidDomainEvents"]
    Research["实验、评测和日志"]

    Command --> Order
    Menu --> Order
    Parser --> Order
    LLM -. "只产生 JobSpec" .-> Order
    Order --> Compiler
    Compiler --> Validators
    Order --> Runtime
    Runtime --> Behavior
    Runtime --> Tasks
    Tasks --> Gameplay
    Gameplay --> Actions
    Actions --> Minecraft
    Runtime --> Events
    Order --> Events
    Events -. "只读观察" .-> Research
~~~

## 主要模块

| 模块 | 入口 | 职责 |
|---|---|---|
| 实体外壳 | entity/AiPartnerEntity | Minecraft 生命周期、同步模式、背包、装备和交互 |
| 行为控制器 | core/behavior/MaidBehaviorController | 手动指令、任务显示模式和 GUI 暂停 |
| 手动指令 | core/behavior/ManualDirective | NONE、FOLLOW、STAY、预留 RETURN_HOME |
| 订单服务 | core/order/MaidOrderService | 输入端共用的验证、事件和调度入口 |
| 契约编译器 | contract/ContractCompiler | 所有权、维度、任务定义和参数形状验证 |
| 验证器注册表 | contract/TaskContractValidatorRegistry | 任务专属前置条件，无中央任务 switch |
| 任务运行时 | core/task/MaidTaskRuntime | 应用、替换、取消、tick、暂停、终态和恢复 |
| 任务接口 | core/task/MaidTask | 统一 start、tick、pause、restore、snapshot 和 stop |
| 任务注册表 | core/task/MaidTaskRegistry | JobType/稳定任务 ID 到实例工厂的映射 |
| 任务快照 | core/task/MaidTaskSnapshot | 版本化整数、长整数和字符串状态 |
| 执行策略 | core/task/TaskExecutionPolicy | 运行时监控和局部恢复能力，不依赖实验枚举 |
| 基础动作 | core/action | 导航、方块破坏和容器物品转移 |
| 任务适配器 | gameplay/task | 把 v0.4 状态机接入统一 MaidTask 生命周期 |
| 领域事件 | core/event | 验证和契约生命周期的只读事件 |
| 实验桥接 | experiment/ExperimentEventBridge | 把领域事件转换为冻结实验日志 |

## 实体职责

AiPartnerEntity 不再：

- 持有 CollectBlockExecutor、DepositItemExecutor 或 CollectAndDepositExecutor；
- 根据 JobType 切换具体任务；
- 保存任务专属进度字段；
- 解析 SystemVariant；
- 直接调用 ExperimentLogger；
- 自行控制契约生命周期。

实体继续负责：

- 原版 TamableAnimal 主人关系；
- 同步有效 PartnerMode；
- 36 格 v0.4 背包和原生装备；
- 右键、GUI、死亡、掉落和存档钩子；
- Goal 和 Navigation 注册；
- 把服务端 tick 委托给 MaidTaskRuntime。

PartnerMode 只是一项面向客户端、命令和旧存档的显示投影，不再是完整权威状态。

## 行为状态

v0.5 将权威状态拆为：

| 状态 | 保存位置 | 说明 |
|---|---|---|
| ManualDirective | MaidBehaviorController | FOLLOW、STAY 等长期覆盖 |
| ActiveTask | MaidTaskRuntime | 唯一有限任务 |
| PartnerMode | SynchedEntityData | 客户端显示投影 |
| inventoryMenuOpen | MaidBehaviorController | 不持久化的暂停覆盖 |

### FOLLOW

- 契约进入 RUNNING；
- ManualDirective 设置为 FOLLOW；
- Follow Goal 根据 5 格启动、3 格停止的滞回区间工作；
- 寻路持续卡住且距离至少 12 格后尝试安全传送；
- GUI 打开时暂停移动；
- 主人失效时按既有规则失败。

### STAY

- 契约进入 RUNNING；
- ManualDirective 设置为 STAY；
- 立即停止导航；
- 空闲漫步被禁止。

### CANCEL

CANCEL 仍保留在外部 Job DSL 中，以兼容命令、解析器和实验数据，但不注册 MaidTask：

~~~text
取消旧契约
→ 清除任务和手动指令
→ 停止导航
→ 创建 CANCEL 契约审计记录
→ RUNNING
→ 立即 COMPLETED
~~~

## 订单与验证

命令、GUI 和自然语言入口统一调用 MaidOrderService。

处理流程：

~~~text
JobSpec
→ ContractCompiler 通用检查
→ TaskDefinitionRegistry 参数边界
→ TaskContractValidatorRegistry 专属检查
→ OrderValidationEvent
→ 接受时交给 MaidTaskRuntime
~~~

ContractCompiler 不再包含按任务增长的大型 switch。当前注册：

- FOLLOW 基础指令验证；
- STAY 基础指令验证；
- CANCEL 基础操作验证；
- CollectBlockContractValidator；
- DepositItemContractValidator；
- CollectAndDepositContractValidator。

新增任务时应同时注册：

1. TaskDefinition；
2. TaskContractValidator；
3. 有限工作才需要 MaidTask 工厂；
4. 命令或 GUI 入口。

不需要修改 AiPartnerEntity 或 MaidTaskRuntime。

## 任务生命周期

MaidTask 统一提供：

~~~text
id
start(context)
restore(context, snapshot)
tick(context)
pauseForTick()
stop()
isRunning()
displayedMode()
executionState()
snapshot()
~~~

MaidTaskRuntime 保证同一时刻最多存在一个活动任务。

任务替换：

~~~text
新契约通过验证
→ 旧契约标记 CANCELLED
→ 停止旧任务和导航
→ 创建注册任务
→ 新契约标记 RUNNING
~~~

任务终态由 MaidTaskResultSink 回报。结果回调绑定契约 UUID，因此旧执行器的迟到回调不能结束新契约。

## 当前有限任务

### COLLECT_BLOCK

~~~text
SEARCH_TARGET
→ NAVIGATE
→ CHECK_TARGET
→ BREAK_BLOCK
→ PICK_UP
→ CHECK_GOAL
→ SEARCH_TARGET / COMPLETE
~~~

### DEPOSIT_ITEM

~~~text
SEARCH_CONTAINER
→ NAVIGATE
→ CHECK_CONTAINER
→ DEPOSIT
→ CHECK_GOAL
→ SEARCH_CONTAINER / COMPLETE
~~~

### COLLECT_AND_DEPOSIT

~~~text
COLLECTING
→ 采集目标成立
→ DEPOSITING
→ 存放目标成立
→ COMPLETE
~~~

组合任务只有一个父契约和一个总超时；输入端不能改变阶段顺序。

三个 v0.4 执行器仍保留内部状态机，但只能通过 gameplay/task 适配器被任务注册表创建。后续工作可以逐步迁移到更细的动作组合，而无需再次修改实体。

## 基础动作

v0.5 已抽取当前任务真正复用的动作：

- NavigateAction：向方块中心导航和停止导航；
- BreakBlockAction：主手挥动和以女仆为破坏者修改方块；
- TransferItemAction：在容器间守恒地移动精确数量物品；
- MaidActions：一个任务实例的动作集合。

尚未有实际玩法调用的放置、交互和装备租约不会预先建立空实现；在对应 v0.6/v0.7 功能落地时加入。

## 暂停、恢复和异常

- GUI 打开时停止导航；
- 活动任务每 tick 延长自己的旧版截止时间，因此 GUI 操作不消耗任务预算；
- GUI 关闭后任务从原状态继续并在动作前复验；
- 任务抛出未预期运行时异常时，运行时记录错误并以 INTERNAL_ERROR 安全失败；
- 目标消失、路径失败等仍根据 TaskExecutionPolicy 决定局部恢复或直接失败；
- 运行时监控和局部恢复能力已与 SystemVariant 解耦；
- v0.4 变体字符串只在旧存档迁移函数中出现。

## 持久化

实体写入 AiPartnerDataVersion = 1。

### 通用字段

~~~text
ManualDirective
PartnerMode
CurrentOrderSource
RuntimeMonitoringEnabled
LocalRecoveryEnabled
RuntimeRecoveryCount
ActiveTaskId
ActiveTaskSnapshotVersion
ActiveTaskSnapshot*
~~~

### 契约字段

~~~text
ContractId
ContractJobType
ContractTarget
ContractQuantity
ContractRadius
ContractStatus
ContractFailureCode
ContractAcceptedAt
ContractMaxLocalRetries
ContractMaxLlmReplans
ContractTimeoutSeconds
~~~

### v0.4 兼容字段

迁移期继续读写：

~~~text
PartnerMode
CollectInitialTargetCount
DepositMovedCount
CompositePhase
CurrentSystemVariant
~~~

加载规则：

1. 优先读取 ActiveTaskId 和通用任务快照；
2. 缺少时按 ContractJobType 创建注册任务；
3. 调用任务自己的 readLegacySnapshot 读取 v0.4 进度；
4. FOLLOW/STAY 从旧 PartnerMode 迁移为 ManualDirective；
5. 未知任务安全标记 INTERNAL_ERROR；
6. 首次实体 tick 延迟恢复底层执行器；
7. 如果恢复前再次保存，保留尚未应用的原快照。

任务失败策略现在也会完整持久化，不再在重启后无条件回退默认值。

## 领域事件与实验隔离

核心发布两类事件：

- OrderValidationEvent；
- ContractLifecycleEvent。

ExperimentEventBridge 是唯一把这些事件交给 ExperimentLogger 的适配器。监听器异常会被隔离，不能中断任务。

命令中的模型调用、批处理和离线评测仍属于外围研究工具；核心任务运行不导入这些包。v0.4 协议继续冻结，v0.5 的实验实现指纹已纳入新的运行时、注册表和任务适配器。

## 线程模型

- 世界读取、契约验证、任务调度和领域事件发布发生在服务器线程；
- LLM HTTP 和响应读取仍在异步链中执行；
- 模型结果通过 MinecraftServer.execute 回到服务器线程；
- 日志使用独立单线程执行器；
- 同一玩家的新模型请求继续取消旧请求；
- CANCEL 始终优先走本地即时解析；
- MaidTaskRuntime 不创建后台线程。

## 安全边界

- 所有入口执行主人、维度、参数形状和任务专属验证；
- 所有世界修改在实际动作前重新检查；
- 采集仍受 mobGriefing、目标白名单、工具、背包和原点半径限制；
- 存放仍只允许主人可打开、未阻挡且容量足够的普通单箱；
- TransferItemAction 只按实际插入量缩减源堆栈；
- 客户端状态和按钮不能直接修改实体契约；
- 模型不能提交坐标、代码或逐 tick 动作。

## v0.5 明确未改变的内容

- 背包仍为 v0.4 的 36 个普通储物格加护甲和副手，主手迁移属于 v0.6；
- 仍为默认单玩家、单女仆查找逻辑；
- 仍只支持三种原木；
- 没有日程、回家、睡眠、战斗、农业、挖矿、熔炼或钓鱼；
- 没有通用模组权限/领地兼容；
- 没有新增 LLM 能力；
- 没有改变 v0.4 任务数值和实验场景。
