# AI Partner 基础模组架构与实施路线

> 基线版本：v0.4.0  
> 文档状态：v0.5 地基重构已实现；v0.6 及以后仍为规划  
> 目标：在继续 LLM、论文实验和评测之前，建立可扩展、可测试、低耦合的基础女仆模组架构。

v0.5 的实际代码结构、兼容字段和当前限制见 [ARCHITECTURE.md](./ARCHITECTURE.md)。本路线中生命周期、日程、主手背包迁移和新增工作仍属于后续版本。

## 1. 背景与目标

AI Partner v0.4.0 已经完成了一个范围较窄但流程闭环的纵向原型：

- 服务端权威的女仆实体和主人关系；
- 跟随、待命和取消；
- 原木采集、存箱及采集后存箱；
- 36 格储物区、护甲和副手；
- GUI、命令和自然语言入口；
- 任务契约、运行时复验、暂停、失败和持久化；
- LLM、实验、评测及日志支持。

现阶段的目标不是继续扩大 LLM 能力，而是把项目发展成一个本身就完整、稳定、可长期扩展的女仆模组。自然语言和实验系统以后只作为外围适配器接入，不参与基础行为执行。

本设计参考 [TouhouLittleMaid 1.16.5](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.16.5) 的活动调度、工作模式、任务注册和日程位置等思想，但不复制其具体代码、内容体系或旧版 Forge API。AI Partner 当前基于现代 Fabric，最终实现应围绕现有版本的原版实体、组件、导航和数据 API 重新设计。

相关现有文档：

- [README.md](../README.md)
- [ARCHITECTURE.md](./ARCHITECTURE.md)

## 2. 产品范围

### 2.1 保留和新增的能力

| 领域 | 目标能力 |
|---|---|
| 生命周期 | 命令生成、主人绑定、每位主人数量限制、喂食、死亡、存档 |
| 行为模式 | 跟随、待命、返回活动地点、回家约束、睡眠 |
| 日程 | 日班、夜班、全天；独立的工作、休闲和睡眠地点 |
| 装备与背包 | 36 格中包含一个主手槽，四个护甲槽和一个副手槽 |
| 基础能力 | 双手与护甲、物品和箭拾取、经验拾取、经验修补、名称 |
| 战斗 | 原版风格近战、弓箭、自卫和保护主人 |
| 农业 | 普通作物、甘蔗、瓜类、可可、花草、除雪 |
| 采集与照料 | 蜂蜜、剪毛、挤奶、喂主人、喂动物繁殖 |
| 环境工作 | 插火把、灭火、砍树、挖矿 |
| 工作站 | 使用原版熔炉烧炼 |
| 其他工作 | 原版风格钓鱼、现有存箱和组合任务 |
| 成长 | 好感度和成长经验，不使用 P 点或东方能力 |
| 外观与表达 | 64×64 原版皮肤、名称、内置语音、简单聊天气泡 |

### 2.2 明确排除的能力

以下内容不进入基础模组的架构和路线：

- 祭坛、祭坛合成、蛋糕生成女仆；
- 东方战斗体系、P 点、博丽御币、弹幕实体、妖精；
- 五子棋、电脑、键盘、椅子、雕像、书架和其他娱乐设施；
- 自定义工作台、末影箱、熔炉、储罐等功能背包；
- 饰品系统；
- 墓碑、胶片复活、神社复活；
- 相机、照片、智能平板收纳、喇叭召回、狐之卷轴定位；
- Bedrock/Gecko 自定义模型、JS 动画和用户声音包；
- 车库套件、女仆信标、红石模型切换器、无线 IO；
- 自动化设施和第三方模组兼容；
- 跨维度执行任务、多女仆编队和多人协作；
- 自主长期规划、长期记忆和复杂人物关系系统。

### 2.3 容易混淆的范围说明

需求中部分名称同时出现在排除项和保留项中，本文按以下口径处理：

- **熔炉**：不实现 TouhouLittleMaid 的特殊熔炉或功能背包，但允许女仆操作附近的原版熔炉。
- **战斗**：删除东方战斗和 P 点系统，但保留原版近战、弓箭和防御主人。
- **语音**：保留模组内置的有限语音事件，不实现用户声音包。
- **聊天气泡**：保留简单的服务端文本气泡，不实现脚本、主题包或复杂聊天系统。
- **外观**：只支持原版 64×64 皮肤和经典/瘦臂选择，不实现模型、动画和娱乐外观系统。
- **死亡与运输**：女仆死亡后永久移除，不复活；运输只保留跟随模式下的原版式安全传送。

## 3. v0.4.0 现状评估

### 3.1 可以保留的基础

- 所有任务均由服务端创建和验证；
- 采集、存箱拥有明确的阶段状态；
- 目标和容器会在交互前重新检查；
- GUI 打开期间会暂停任务，关闭后继续；
- 跟随具有起停距离迟滞、寻路重试和延迟传送；
- 主人、背包、装备、契约和组合任务阶段能够持久化；
- 当前 LLM 只产生高层意图，没有直接控制每个 tick。

### 3.2 必须优先处理的耦合

#### 实体承担职责过多

[AiPartnerEntity.java](../src/main/java/io/github/ozozorz/aipartner/entity/AiPartnerEntity.java) 当前同时承担：

- 实体数据同步；
- 主人和交互；
- 背包及装备；
- 模式切换；
- 契约应用；
- 三个具体任务执行器的创建和 tick；
- 执行器恢复；
- GUI 暂停；
- 实验变体和日志。

继续加入农业、战斗、挖矿、熔炼和钓鱼会使其迅速变成难以测试的巨型状态机。

#### 模式概念混合

当前 PartnerMode 同时包含空闲、跟随、待命、采集和存箱。它混合了：

- 长期手动指令；
- 日程活动；
- 工作类型；
- 当前执行器状态。

这些概念应拆开保存和仲裁。

#### 任务分发依赖大型分支

[ContractCompiler.java](../src/main/java/io/github/ozozorz/aipartner/contract/ContractCompiler.java) 和实体中的任务分发主要依赖 JobType 分支。新增任务会要求同时修改枚举、注册表、编译器、实体、GUI 和命令。

#### GUI 与研究系统耦合

[AiPartnerMenu.java](../src/main/java/io/github/ozozorz/aipartner/inventory/AiPartnerMenu.java) 会直接创建契约并记录实验数据。GUI 应只提交标准化请求，由服务层处理验证和替换。

#### 服务和渲染仍以单女仆原型为假设

- [PartnerService.java](../src/main/java/io/github/ozozorz/aipartner/service/PartnerService.java) 通过扫描已加载世界寻找主人女仆，不适合可配置数量。
- [AiPartnerRenderer.java](../src/main/java/io/github/ozozorz/aipartner/client/render/AiPartnerRenderer.java) 固定使用 Alex 皮肤，但其原版人形渲染基础可以继续使用。

## 4. 核心设计原则

1. **实体保持轻量**  
   实体只负责 Minecraft 生命周期、同步字段、原生装备和行为运行时入口。

2. **一个服务端入口**  
   命令、GUI、本地规则解析器和可选 LLM 都提交相同的 MaidOrderRequest。

3. **长期模式与有限任务分离**  
   跟随、待命、日程和工作模式是长期策略；采集 N 个物品等是有限任务。

4. **工作模式不直接操作世界**  
   工作模式选择下一项有限任务，有限任务通过通用动作执行。

5. **任务通过注册表扩展**  
   新任务注册 TaskType、Validator、ExecutorFactory 和 SnapshotCodec，不修改实体。

6. **世界修改统一复验**  
   所有破坏、放置、攻击、喂食和容器操作在执行前重新检查。

7. **研究系统只观察核心事件**  
   LLM、实验、评测和日志可以订阅领域事件，但核心代码不能反向依赖它们。

8. **持久化必须版本化**  
   每个运行中任务保存任务 ID、快照版本、阶段和业务进度，并提供迁移策略。

9. **优先使用原版语义**  
   食物、装备、附魔、掉落物、工具耐久、熔炉和钓鱼尽量复用原版逻辑。

10. **不为未计划的兼容性预留复杂抽象**  
    只抽象当前确实共享的行为，不建立第三方模组兼容层。

## 5. 目标架构

~~~mermaid
flowchart TB
    subgraph Input["输入适配层"]
        Command["/maid 命令"]
        GUI["女仆 GUI"]
        Natural["本地规则 / 可选 LLM"]
    end

    OrderService["MaidOrderService<br/>提交、校验、替换、取消"]
    Controller["MaidBehaviorController<br/>统一行为仲裁"]
    Schedule["ScheduleController<br/>日班、夜班、全天"]
    Runtime["TaskRuntime<br/>暂停、恢复、超时、持久化"]
    Registry["TaskRegistry<br/>任务 ID → 执行器工厂"]
    WorkMode["WorkMode<br/>根据工作配置产生有限任务"]

    subgraph Actions["可复用动作层"]
        Navigate["NavigateAction"]
        Interact["InteractAction"]
        Break["BreakBlockAction"]
        Place["PlaceBlockAction"]
        Transfer["TransferItemAction"]
        Equip["EquipmentLease"]
        Attack["CombatAction"]
    end

    subgraph Domain["核心领域服务"]
        Ownership["OwnershipService"]
        Inventory["Inventory / Pickup / XP"]
        Growth["Favorability / Growth"]
        Locations["ActivityLocation"]
        Skin["SkinMetadataService"]
    end

    Minecraft["Minecraft 实体、世界、导航、方块实体"]
    Events["MaidDomainEvent"]
    Research["实验、评测、日志<br/>可选旁路"]

    Command --> OrderService
    GUI --> OrderService
    Natural -. "只产生结构化请求" .-> OrderService

    OrderService --> Controller
    Schedule --> Controller
    Controller --> WorkMode
    Controller --> Runtime
    WorkMode --> Runtime
    Runtime --> Registry
    Registry --> Actions
    Actions --> Minecraft
    Domain --> Controller

    Controller --> Events
    Runtime --> Events
    Events -. "只订阅，不反向依赖" .-> Research
~~~

### 5.1 建议的包结构

~~~text
io.github.ozozorz.aipartner
├── core
│   ├── behavior
│   │   ├── MaidBehaviorController
│   │   ├── ManualDirective
│   │   ├── ScheduleActivity
│   │   └── InterruptPolicy
│   ├── task
│   │   ├── MaidTask
│   │   ├── TaskRuntime
│   │   ├── TaskRegistry
│   │   ├── TaskContext
│   │   ├── TaskSnapshot
│   │   └── TaskResult
│   ├── action
│   │   ├── NavigateAction
│   │   ├── BreakBlockAction
│   │   ├── PlaceBlockAction
│   │   ├── InteractAction
│   │   ├── TransferItemAction
│   │   └── EquipmentLease
│   ├── schedule
│   ├── ownership
│   ├── inventory
│   ├── growth
│   └── event
├── gameplay
│   ├── combat
│   ├── farming
│   ├── animal
│   ├── support
│   ├── lumber
│   ├── mining
│   ├── smelting
│   └── fishing
├── adapter
│   ├── command
│   ├── menu
│   ├── localparser
│   └── llm
├── client
│   ├── render
│   ├── skin
│   ├── voice
│   └── chat
└── research
    ├── experiment
    ├── evaluation
    └── logging
~~~

初期可以只建立包级边界，不必立即拆成多个 Gradle 模组。

## 6. 行为模型

### 6.1 正交状态维度

不应再使用一个枚举表达所有行为。建议拆成：

| 维度 | 示例 | 说明 |
|---|---|---|
| ManualDirective | NONE、FOLLOW、STAY、RETURN_HOME | 玩家设置的长期覆盖指令 |
| ScheduleActivity | WORK、LEISURE、SLEEP | 当前时间对应的日程活动 |
| WorkMode | FARM、LUMBER、MINING、FISHING 等 | 工作时持续选择任务的策略 |
| ActiveTask | collect_log、smelt_item 等 | 有明确开始和结束的有限任务 |
| InterruptState | COMBAT、GUI_PAUSED、OWNER_OFFLINE | 临时中断，不覆盖持久化意图 |

### 6.2 行为状态机

~~~mermaid
stateDiagram-v2
    [*] --> Runtime

    state Runtime {
        [*] --> Reevaluate

        state "重新仲裁" as Reevaluate
        state "跟随主人" as Follow
        state "原地待命" as Stay
        state "返回活动地点" as ReturnHome
        state "执行有限任务" as Order
        state "日程工作" as Work
        state "休闲活动" as Leisure
        state "睡眠" as Sleep
        state "防御战斗" as Combat

        Reevaluate --> Combat: 存在有效威胁
        Reevaluate --> Order: 有手动任务
        Reevaluate --> Follow: FOLLOW 指令
        Reevaluate --> Stay: STAY 指令
        Reevaluate --> ReturnHome: RETURN_HOME 指令
        Reevaluate --> Work: 工作时段
        Reevaluate --> Leisure: 休闲时段
        Reevaluate --> Sleep: 睡眠时段

        Order --> Reevaluate: 完成、失败或取消
        ReturnHome --> Reevaluate: 到达或不可达
        Work --> Reevaluate: 时段或配置变化
        Leisure --> Reevaluate: 时段或配置变化
        Sleep --> Reevaluate: 醒来或受攻击
        Follow --> Reevaluate: 取消或新命令
        Stay --> Reevaluate: 取消或新命令
        Combat --> Reevaluate: 威胁消失
    }

    state "GUI 暂停" as Paused
    state "主人离线暂停" as Offline
    state "死亡并移除" as Dead

    Runtime --> Paused: 打开 GUI
    Paused --> Runtime: 关闭并重新检查
    Runtime --> Offline: 主人离线
    Offline --> Runtime: 主人返回
    Runtime --> Dead: 生命值归零
    Dead --> [*]
~~~

### 6.3 仲裁优先级

~~~text
死亡
→ 紧急自卫
→ GUI 或主人离线暂停
→ 玩家新提交的任务或指令
→ 当前日程活动
→ 空闲观察与低频漫步
~~~

行为规则：

- FOLLOW、STAY 或新任务会替换上一个手动指令/任务；
- CANCEL 不是持久化任务类型，而是清除当前任务和指令后重新仲裁；
- STAY 禁止离开锚点，但允许有限距离内自卫；
- 睡眠受到攻击时会醒来；
- 打开 GUI 时暂停任务计时，但遭到攻击时可以强制退出 GUI 并自卫；
- 战斗结束、GUI 关闭或主人重新上线后，都先重新验证任务，不直接从旧动作继续。

## 7. 任务系统

### 7.1 统一生命周期

每种任务实现统一接口：

~~~text
start(context)
tick(context)
pause(reason)
resume(context)
cancel(reason)
snapshot()
restore(snapshot)
~~~

TaskContext 只提供任务需要的能力：

- 女仆、主人和服务端世界；
- 背包和装备访问；
- 导航和交互动作；
- 服务器规则和任务配置；
- 当前活动地点；
- 运行时间和事件输出。

任务不直接访问 LLM、GUI、实验变体或日志文件。

### 7.2 通用执行流程

~~~mermaid
flowchart LR
    Search["搜索目标"] --> Navigate["导航"]
    Navigate --> Validate["现场复验"]
    Validate --> Prepare["装备工具<br/>占用资源"]
    Prepare --> Act["执行动作"]
    Act --> Collect["收集或转移产物"]
    Collect --> Goal["检查目标"]
    Goal --> Complete["完成"]
    Goal --> Search

    Navigate --> Retry["有限重试"]
    Validate --> Retry
    Retry --> Search

    Validate --> Failed["结构化失败"]
    Prepare --> Failed
    Act --> Failed
~~~

不同任务共享流程和动作，但拥有自己的：

- 目标查找器；
- 前置条件验证器；
- 工具选择规则；
- 执行动作；
- 成功条件；
- 失败策略；
- 快照编码器。

### 7.3 组合任务

现有 COLLECT_AND_DEPOSIT 建议改造成服务端定义的固定序列：

~~~text
SequenceTask
├── CollectBlockTask
└── DepositItemTask
~~~

组合阶段、基线数量和已完成数量统一保存在组合任务快照中。LLM 只能请求已经注册的组合，不能任意调整阶段顺序或插入动作。

### 7.4 超时

- 超时按实际运行 tick 计算；
- GUI、主人离线、合法暂停和区块未加载期间不消耗超时；
- 不再让所有任务共用固定 90 秒；
- 导航、采集、熔炼和钓鱼分别定义合理的软超时和硬超时；
- 超时后先执行任务自己的恢复策略，再产生最终失败。

## 8. 生命周期、主人与数量限制

### 8.1 生成和绑定

- /maid spawn 在玩家附近寻找安全位置；
- 创建成功后立即写入主人 UUID；
- 新女仆获得唯一 maid UUID 和默认名称；
- 生成过程由服务端完成，客户端不能自行声明所有权。

### 8.2 数量索引

新增服务器持久化 MaidOwnershipState：

~~~text
owner UUID
└── maid UUID set
~~~

行为要求：

- 默认 maxMaidsPerOwner = 1；
- 配置可以提高上限，但不提供编队或协作；
- 生成、加载、死亡和移除时更新索引；
- 定期或加载时清理失效 UUID；
- TamableAnimal 的主人 UUID 仍是实体所有权事实来源；
- 索引用于快速查询和数量限制。

如果允许一位主人拥有多只女仆，命令应引入“当前选中女仆”或显式 maid ID，不能继续扫描所有维度后选择最近实体。

### 8.3 喂食

- 使用当前 Minecraft 版本的原版食物/可消耗组件判断玩家是否可食用；
- 不维护食物 ID 白名单；
- 默认只允许主人喂食；
- 消耗一个物品并正确返还碗、瓶等容器；
- 应用原版食物效果，包括可能的负面效果；
- 按营养值治疗，但不超过最大生命；
- 通过来源冷却增加少量好感度；
- 初期不增加完整饥饿值系统。

### 8.4 死亡

- 生命值归零后按原版流程死亡并永久移除；
- 背包和装备掉落，避免玩家物品无提示消失；
- 失败当前任务并发送领域事件；
- 清理主人索引；
- 不创建墓碑、复活记录或运输物品。

## 9. 跟随、待命、回家与睡眠

### 9.1 跟随

保留当前 [AiPartnerFollowOwnerGoal.java](../src/main/java/io/github/ozozorz/aipartner/entity/goal/AiPartnerFollowOwnerGoal.java) 的距离迟滞和延迟传送思想：

- 靠近主人时停止导航；
- 定期重新计算路径；
- 长时间无进展且距离足够远时尝试传送；
- 只在 FOLLOW 指令下传送；
- 只允许同一维度；
- 检查脚下、碰撞、液体和危险方块；
- 找不到安全位置时继续寻路或报告受阻；
- 工作、待命、睡眠和返回活动地点时不得传送。

### 9.2 待命

- 停止当前导航；
- 记录待命锚点；
- 禁止离开配置半径；
- 允许看向玩家、转头和原地防御；
- 新命令、取消或主人改变模式后退出。

### 9.3 回家约束和活动地点

每个地点保存：

~~~text
dimension
block position
allowed radius
~~~

需要三个独立地点：

- workLocation；
- leisureLocation；
- sleepLocation。

另外保存 homeBound 开关：

- 开启时，日程活动必须在对应区域执行；
- 未设置某个地点时，可回退到默认 home 位置；
- FOLLOW 临时忽略 homeBound；
- FOLLOW 取消后重新回到日程区域；
- 不进行跨维度返回或传送。

### 9.4 睡眠

- 在睡眠时段前往 sleepLocation；
- 优先使用范围内可用的原版床；
- 没有床时在安全位置进入休息姿态并周期性重试；
- 睡眠不改变世界时间；
- 受攻击、床失效或日程切换时醒来；
- 可以配置睡眠期间的缓慢治疗。

## 10. 日程系统

提供三种预设：

| 日程 | 白天 | 黄昏/过渡 | 夜间 |
|---|---|---|---|
| 日班 | 工作 | 休闲 | 睡眠 |
| 夜班 | 睡眠 | 休闲 | 工作 |
| 全天 | 工作 | 无任务时休闲 | 工作 |

具体时间窗口应存放在服务端配置或数据定义中，而不是散落在 AI Goal 内。

日程只决定期望活动，不直接操作导航：

~~~text
时间变化
→ ScheduleController 计算 ScheduleActivity
→ MaidBehaviorController 仲裁手动指令和中断
→ LocationController 选择活动地点
→ WorkMode 或空闲行为执行
~~~

GUI 至少支持：

- 选择日班、夜班或全天；
- 使用当前位置设置工作、休闲和睡眠地点；
- 清除地点；
- 设置活动半径；
- 开关 homeBound；
- 显示当前日程活动和下一次切换。

## 11. 背包、装备和工具

### 11.1 槽位模型

最终模型：

~~~text
36 个女仆物品格
├── 1 个原生主手槽
└── 35 个普通储物槽

额外装备
├── 头
├── 胸
├── 腿
├── 脚
└── 原生副手
~~~

主手必须映射到 Minecraft 原生 EquipmentSlot.MAINHAND，确保：

- 正确渲染；
- 攻击属性生效；
- 工具交互和耐久生效；
- 弓箭、鱼竿和方块放置拥有一致语义。

不能同时维护“GUI 主手副本”和“实体原生主手”，否则容易复制或丢失物品。

### 11.2 v0.4.0 存档迁移

当前存档拥有 36 个普通储物格，没有 GUI 主手格。迁移规则：

1. 将旧第 0 格迁入原生主手；
2. 如果原生主手已有物品，将旧第 0 格放入第一个空储物格；
3. 旧第 1—35 格保持顺序；
4. 如果没有可用空间，物品安全掉落到女仆附近并记录迁移事件；
5. 迁移设置 dataVersion，禁止重复执行。

### 11.3 工具租约

任务通过 EquipmentLease 临时使用工具：

1. 从储物区选择合适工具；
2. 记录原主手物品和来源槽；
3. 将工具放入主手；
4. 执行任务并应用附魔、耐久和损坏；
5. 完成、失败、中断或重启恢复时归还；
6. 如果 GUI 中途改变槽位，按实际物品重新协调，绝不覆盖玩家修改。

## 12. 拾取、经验与修补

### 12.1 物品和箭

PickupService 作为后台能力运行：

- 拾取允许拾取的 ItemEntity；
- 拾取能够转换为物品的箭；
- 检查背包空间；
- 支持 GUI 开关、范围和简单过滤；
- 不再依赖 CollectBlockExecutor；
- 任务生成的掉落物可以提高拾取优先级；
- STAY 时只拾取锚点附近物品。

### 12.2 经验与经验修补

拾取经验球时：

1. 查找受损且带经验修补的主手、副手或护甲；
2. 按原版经验修补转换规则修复装备；
3. 剩余经验进入成长经验；
4. 发送经验变化事件；
5. 不引入 P 点或独立魔力资源。

好感度与成长经验是两个不同概念：

- 好感度描述主人关系；
- 成长经验描述工作和战斗成长。

## 13. 战斗

### 13.1 战斗策略

建议提供：

- OFF；
- SELF_DEFENSE；
- DEFEND_OWNER。

默认使用 DEFEND_OWNER。

合法目标：

- 正在攻击女仆的生物；
- 正在攻击主人的生物；
- 主人最近合法攻击的敌对目标，可作为可选策略。

始终排除：

- 主人；
- 玩家，除非未来明确增加 PVP 配置；
- 同主人的女仆和宠物；
- 无法合法伤害的实体。

### 13.2 近战与弓箭

- 根据当前可用武器、弹药和距离选择战术；
- 弓箭要求可用弓和箭；
- 近战追击受最大距离和活动区域限制；
- STAY 模式只能在锚点附近作战；
- 防止无休止追击或跨越危险区域；
- 战斗结束后恢复被中断任务并重新验证目标；
- 使用原版属性、附魔、冷却、伤害和耐久逻辑。

战斗是高优先级中断能力，不应实现成普通工作 JobType。

## 14. 工作能力

### 14.1 工作模式与有限任务

WorkMode 是持续策略，例如 FARMER 或 LUMBERJACK；它只负责在工作时段选择下一项有限任务。

例如：

~~~text
FARMER WorkMode
→ 扫描活动区域
→ 选择 HarvestCropTask
→ 等待任务结束
→ 冷却
→ 再次扫描
~~~

同一个有限任务也可以被命令直接调用，从而避免“自动工作”和“命令任务”实现两套逻辑。

### 14.2 功能矩阵

| 能力 | 第一版行为 | 关键约束 |
|---|---|---|
| 普通作物 | 收获成熟作物并补种 | 必须有种子；保留耕地 |
| 甘蔗 | 破坏第二层及以上部分 | 保留根部 |
| 瓜类 | 破坏南瓜/西瓜果实 | 不破坏茎 |
| 可可 | 收获成熟可可并补种 | 检查丛林原木附着面 |
| 花草 | 按配置采集草和花 | 工具、掉落和背包复验 |
| 除雪 | 清除区域内雪层 | 需要锹；限制区域 |
| 蜂蜜 | 使用瓶或剪刀采集 | 蜂巢成熟；遵循原版蜂群风险 |
| 剪毛 | 剪可剪毛的成年实体 | 需要剪刀；检查冷却状态 |
| 挤奶 | 对成年奶牛使用桶 | 需要空桶和背包空间 |
| 喂主人 | 饥饿低于阈值时使用安全食物 | 默认排除负面食物；冷却 |
| 喂动物 | 对可繁殖成年动物使用对应食物 | 繁殖冷却和附近数量上限 |
| 插火把 | 低亮度位置放置火把 | 间距、表面和数量限制 |
| 灭火 | 熄灭燃烧实体和邻近火方块 | 限定活动区；遵循世界修改规则 |
| 砍树 | 识别自然树并分段砍伐 | 连通上限、树叶检查、工具和掉落 |
| 挖矿 | 搜索暴露且可到达的配置矿石 | 第一版不自主挖隧道 |
| 熔炼 | 操作原版熔炉并等待产物 | 工作站租约、燃料、阶段持久化 |
| 钓鱼 | 抛竿、等待、咬钩、收竿 | 使用真实浮标流程和战利品语义 |
| 存箱 | 将匹配物品放入合法容器 | 主人权限、容量、容器复验 |

### 14.3 农业规则

- 使用成熟度和方块类型规则，不硬编码单一作物；
- 每个作物族实现独立 WorkRule；
- 收获与补种是一个原子业务步骤；
- 补种失败时不得继续清空大片农田；
- 使用正确工具、附魔、掉落和耐久；
- 工作半径来自 workLocation；
- 到达扫描预算后分 tick 继续，避免单 tick 遍历大范围。

### 14.4 砍树

为了避免破坏玩家建筑，第一版采用保守的自然树判定：

- 根部位于合理自然方块上；
- 附近存在足够树叶；
- 原木连通数量不超过配置上限；
- 不跨越活动区域；
- 不处理包含方块实体的结构；
- 按从下到上或安全顺序砍伐；
- 每个方块都重新检查工具、背包和 mobGriefing；
- 使用原版战利品和耐久逻辑。

自然树判定失败时应拒绝，而不是把不确定结构当作树。

### 14.5 挖矿

第一阶段只实现：

- 指定矿石或原版矿石标签；
- 已暴露或无需破坏非目标方块即可到达；
- 有界数量和半径；
- 正确镐等级；
- 原版掉落、时运、精准采集和耐久；
- 避开岩浆、危险落差、方块实体和不可破坏方块。

自主挖隧道需要单独的空间规划、回退路线、支撑和液体风险系统，不应混入第一版挖矿。

### 14.6 熔炼

熔炼任务阶段：

~~~text
搜索熔炉
→ 导航
→ 复验并获取工作站租约
→ 插入精确原料
→ 插入足够燃料
→ 等待
→ 复验配方和产物
→ 取回目标数量
→ 释放租约
~~~

必须保存：

- 熔炉维度和坐标；
- 已插入数量；
- 当前阶段；
- 已取回数量；
- 任务目标；
- 租约标识和恢复策略。

如果玩家修改熔炉内容，任务应重新计算，不覆盖玩家物品。

### 14.7 钓鱼

钓鱼任务需要真实状态：

~~~text
寻找安全水边
→ 装备鱼竿
→ 抛出女仆可拥有的浮标
→ 等待有效咬钩
→ 收竿
→ 生成原版钓鱼产物
→ 拾取
→ 重复或完成
~~~

不应通过固定计时后直接抽取战利品表来伪造钓鱼。若当前原版 FishingHook 只能绑定玩家，应实现受限的女仆浮标实体，而不是创建虚假玩家长期驱动行为。

## 15. 好感度和成长

### 15.1 好感度

来源建议：

- 主人喂食；
- 主人治疗；
- 完成工作；
- 保护主人；
- 正常完成睡眠和日程；
- 长时间共同活动的低频奖励。

每种来源都需要单独冷却和每日上限，避免通过重复操作刷取。

### 15.2 成长经验

来源建议：

- 经验球修补后的剩余经验；
- 完成有价值的工作；
- 合法战斗；
- 首次完成不同工作的小额奖励。

### 15.3 成长效果

- 最大生命；
- 基础攻击；
- 少量移动或工作效率；
- 更稳定的寻路重试；
- 可选的外观或语音反馈。

所有核心工作从初始等级即可使用。成长效果通过数据定义或配置控制，不把基础功能锁在好感等级后，也不引入 P 点、魔法或东方能力。

## 16. 名称、皮肤、语音和聊天气泡

### 16.1 名称

- 使用原版 CustomName；
- 支持命名牌和 GUI 修改；
- 检查长度和文本组件安全；
- 名称随实体持久化并在命令选择中显示。

### 16.2 64×64 皮肤

推荐服务端托管流程：

1. 客户端选择本地 PNG；
2. 客户端预检格式、尺寸和文件大小；
3. 分片上传到服务端；
4. 服务端验证主人、距离、上传速率和总大小；
5. 服务端实际解码图片，不信任文件头；
6. 要求尺寸严格为 64×64；
7. 重新编码成标准 PNG；
8. 使用 SHA-256 作为资源 ID；
9. 实体只同步皮肤哈希和 SLIM/CLASSIC 类型；
10. 观察该实体的客户端按哈希请求并缓存动态纹理。

安全要求：

- 禁止任意路径和外部 URL；
- 限制压缩文件和解码后像素大小；
- 相同哈希复用文件；
- 非主人不能修改皮肤；
- 上传失败不影响实体存档，继续使用默认皮肤。

### 16.3 语音

- 只使用模组内置有限 SoundEvent；
- 事件包括问候、受伤、任务接受、完成、失败、睡眠和升级；
- 提供总开关、音量和冷却；
- 不实现声音包、脚本语音或在线语音下载。

### 16.4 聊天气泡

- 服务端发送 Component、持续时间和气泡类型；
- 客户端在女仆头顶渲染短文本；
- 限制文本长度和同时存在数量；
- 不保存长期历史；
- 不执行脚本；
- 与 LLM 无硬依赖，普通任务反馈也可以使用。

## 17. 持久化数据模型

建议建立版本化 MaidPersistentData，至少包含：

~~~text
dataVersion
ownerUuid
maidUuid
manualDirective
homeBound
scheduleType
workLocation
leisureLocation
sleepLocation
selectedWorkMode
workModeSettings
35-slot storage
native mainhand
native offhand
native armor
pickupPolicy
combatPolicy
activeTaskId
activeTaskSnapshotVersion
activeTaskSnapshot
favorability
favorabilityCooldowns
growthXp
growthLevel
skinHash
skinModel
voiceEnabled
chatBubbleEnabled
~~~

不持久化：

- 当前 Path；
- 临时扫描游标以外的大型目标列表；
- 客户端纹理对象；
- 当前动画帧；
- 可以安全重新计算的缓存。

需要持久化：

- 任务业务阶段；
- 已完成数量；
- 当前目标或工作站位置；
- 熔炉和组合任务进度；
- 暂停前的逻辑状态；
- 任务有效运行时间。

## 18. 服务端验证与安全边界

任何输入入口都必须经过相同验证：

- 发起者是否为主人；
- 目标女仆是否存在、存活且可访问；
- 是否在同一维度；
- 请求类型和参数是否合法；
- 数量、范围和位置是否在配置上限内；
- 是否拥有工具、材料和背包空间；
- 世界规则是否允许；
- 目标是否仍存在且可交互；
- 容器或工作站是否允许访问；
- 任务是否可以替换当前任务；
- 上传文件是否安全。

所有世界修改必须在动作发生的同一个服务端 tick 前重新验证。客户端 GUI 显示的状态不能作为执行依据。

## 19. 配置拆分

当前配置主要围绕 LLM。建议拆成：

### GameplayConfig

- maxMaidsPerOwner；
- 跟随和传送距离；
- 工作、扫描和拾取范围上限；
- 战斗策略默认值；
- 食物治疗倍率；
- 日程时间窗；
- 各任务超时和重试；
- 成长数值；
- mobGriefing 相关行为。

### ClientConfig

- 气泡开关；
- 语音音量；
- 模型预览；
- 皮肤缓存大小；
- GUI 显示偏好。

### LlmConfig

- 是否启用；
- API 地址、模型和超时；
- 只属于可选输入适配层。

### ResearchConfig

- 实验变体；
- 日志和评测开关；
- 数据输出位置；
- 不得影响基础任务的语义。

## 20. 实施路线

### Phase 0：冻结 v0.4.0 基线

- 创建明确的 v0.4.0 基线标签或分支；
- 记录当前存档格式；
- 为现有六种任务补充回归测试；
- 保留论文实验材料，但不继续把实验逻辑写入核心实体。

完成条件：

- 当前命令和任务行为有可重复测试；
- 旧存档样本可用于后续迁移；
- 基线实验结果不受重构影响。

### Phase 1 / v0.5：地基重构

- 引入 MaidBehaviorController；
- 拆分 ManualDirective、ScheduleActivity、WorkMode 和 ActiveTask；
- 引入 MaidTask、TaskRuntime、TaskRegistry 和 TaskSnapshot；
- 抽取导航、破坏、放置、交互、转移和装备动作；
- GUI 和命令改为调用 MaidOrderService；
- 核心通过 MaidDomainEvent 输出观察事件；
- 移除实体对具体执行器和 ExperimentLogger 的依赖；
- 建立 dataVersion 和迁移入口。

完成条件：

- 现有功能外部行为保持不变；
- AiPartnerEntity 不再持有具体任务执行器；
- 新任务不需要修改实体；
- core 不导入 LLM、实验、评测或客户端代码；
- v0.4.0 存档能够加载。

### Phase 2 / v0.6：生活、日程和物品基础

- 主人索引和可配置数量；
- 多女仆时的选择机制；
- 任意原版可食用食物；
- 36 格含主手的新背包；
- EquipmentLease；
- 通用物品、箭、经验拾取和经验修补；
- 日班、夜班、全天；
- 工作、休闲、睡眠地点；
- 回家约束和睡眠；
- 名称和皮肤上传；
- 内置语音和简单气泡。

完成条件：

- 存档迁移不丢失或复制物品；
- 三种日程跨重启保持；
- GUI 暂停后正确恢复；
- 跟随以外的模式绝不传送；
- 皮肤上传经过完整服务端校验。

### Phase 3 / v0.7：基础工作包

- 防御战斗、近战和弓箭；
- 普通作物、甘蔗、瓜类、可可；
- 花草和除雪；
- 蜂蜜、剪毛和挤奶；
- 喂主人和喂动物；
- 插火把和灭火；
- 迁移现有采集、存箱和组合任务到统一任务系统。

完成条件：

- 所有工作复用通用动作；
- 战斗中断后可以恢复任务；
- 工具、掉落、耐久和背包行为与原版一致；
- 工作只能在合法地点和范围内发生。

### Phase 4 / v0.8：复杂工作包

- 自然树识别和砍树；
- 暴露矿石采集；
- 原版熔炉任务及工作站租约；
- 真实钓鱼流程；
- 通用物流能力；
- 好感度和成长。

完成条件：

- 砍树不会轻易破坏玩家木结构；
- 挖矿不会自主开隧道或进入明显危险区域；
- 熔炉任务在重启和玩家改动后能够正确恢复或安全失败；
- 钓鱼使用真实浮标和原版战利品语义；
- 成长系统不锁住基础工作。

### Phase 5：稳定性和论文前冻结

- 完整 GameTest 和单元测试；
- 长时间运行和重启恢复测试；
- 性能分析和扫描预算调整；
- 配置文档和用户手册；
- 冻结基础模组行为；
- 在稳定事件接口上重新接入 LLM 和实验系统。

## 21. 测试计划

至少覆盖以下场景：

1. v0.4.0 存档迁移到新背包格式；
2. GUI 快速移动和任务装备切换同时发生；
3. 执行中保存、退出并重启；
4. 主人离线后重新上线；
5. 目标、容器或熔炉被玩家移除；
6. 工具在任务中损坏；
7. 背包在导航期间被填满；
8. FOLLOW 卡住后安全传送；
9. 非 FOLLOW 状态绝不传送；
10. 日程跨时间边界切换；
11. 战斗中断农业或采集后恢复；
12. STAY 状态只在锚点附近自卫；
13. 自然树判定拒绝玩家木结构；
14. 挖矿拒绝未暴露目标和危险目标；
15. 熔炉内容被玩家修改后不覆盖物品；
16. 钓鱼浮标在区块卸载或重启后的清理；
17. 经验优先修补装备，剩余部分进入成长；
18. 女仆死亡后掉落物品并清理主人索引；
19. 非主人操作 GUI、任务和皮肤上传被拒绝；
20. 非法、超大或伪造尺寸的 PNG 被拒绝。

## 22. 基础架构完成标准

在重新推进 LLM 和论文实验前，应满足：

- 核心模组可以在完全禁用 LLM、实验、评测和日志时独立运行；
- AiPartnerEntity 不感知具体工作类型；
- 新任务可以通过注册方式加入；
- 所有有限任务都支持暂停、恢复、取消、替换、存档和重启恢复；
- 日程、手动指令、任务和中断状态具有确定的优先级；
- 所有世界操作都由服务端验证；
- 背包和工具切换不存在已知复制或丢失漏洞；
- 跟随传送严格限制为同维度安全传送；
- 皮肤上传不接受任意路径或未验证数据；
- 死亡永久移除女仆，不存在隐藏复活和运输机制；
- 常用 AI 扫描具有每 tick 预算，不造成明显卡顿；
- GameTest 覆盖核心状态转换和高风险物品流程；
- 基础行为冻结后，实验系统只能通过稳定请求接口和领域事件接入。

## 23. v0.5 已完成的代码改动

v0.5 只完成结构调整，没有新增农业、战斗或挖矿：

1. 已创建 MaidBehaviorController；
2. 已创建 MaidTask 接口和 MaidTaskRuntime；
3. 已创建冻结的 MaidTaskRegistry；
4. 已用任务适配器包裹 CollectBlockExecutor 和 DepositItemExecutor；
5. 已将 COLLECT_AND_DEPOSIT 接入固定两阶段 CollectAndDepositMaidTask；
6. 已将 FOLLOW、STAY 从有限工作改为 ManualDirective；
7. 已将 CANCEL 改为 MaidOrderService 的即时取消操作；
8. 已从 AiPartnerEntity 移除实验状态和具体执行器分支；
9. 已增加 AiPartnerDataVersion、通用任务快照和 v0.4 字段迁移；
10. 已增加任务注册表、验证器注册表、快照 round-trip 和契约恢复测试；
11. 已通过完整 Gradle build，现有 47 项测试全部通过。

下一阶段可以在该边界上实现生命周期、日程、主手背包迁移和通用拾取，而不再修改实体任务分发逻辑。
