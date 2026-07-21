# AI Partner

AI Partner 是一个面向 Minecraft Java Edition 26.1.2 的 Fabric 模组原型。它使用“指令—行为契约（Instruction–Behavior Contract, IBC）”把玩家指令转换为受约束、可验证的伙伴行为。

当前代码版本为 v0.6“生活与日程”里程碑；v0.4 实验数据与协议继续作为冻结基线保留。基础模组已经具备：

- 自定义 AI 女仆实体，默认复用 Minecraft 官方 Alex 瘦臂模型，并支持上传 64×64 PNG 皮肤；
- 持久化主人索引、可配置数量上限，以及多女仆列表和当前女仆选择；
- 36 格物品布局（原生主手 + 35 格储物）、四格护甲、副手和安全工具租约；
- 任意原版可食用食物、通用物品/箭/经验拾取、装备经验修补；
- 日班、夜班、全天工作，以及独立工作/休闲/睡眠地点、活动半径和回家约束；
- 不传送的回家导航、床上睡眠、无床休息、受伤唤醒和睡眠恢复；
- 名称、基础成长/好感数据、内置声音反馈和短时聊天气泡；
- GUI 同步显示生命、模式、任务、契约、日程、地点、半径、好感和成长，并提供对应快捷按钮；
- `FOLLOW`、`STAY`、`COLLECT_BLOCK`、`DEPOSIT_ITEM`、`COLLECT_AND_DEPOSIT`、`CANCEL` 显式状态机；
- `FOLLOW`、`STAY` 已从有限工作中拆分为长期手动指令，`CANCEL` 由统一订单服务执行；
- 通用 `MaidTaskRuntime`、任务/验证器注册表和版本化任务快照；
- 命令、GUI、规则解析器和 LLM 共用同一个服务端订单入口；
- 实验日志通过只读领域事件观察核心运行时，实体与 GUI 不再依赖实验包；
- 固定“采集→存箱”两阶段编排、跨重启阶段恢复和父契约总超时；
- `/maid <message>` 的中英文规则解析降级路径；
- 执行前权限、维度和参数验证；
- 有限寻路重试、类型化失败状态；
- 带 `episode_id`、`scenario_id` 与世界种子的异步 JSONL 实验日志；
- Rule-BT、LLM-Schema、Maid-IBC 与 A2 四种冻结系统条件；
- 18 场景可恢复批处理，以及带限速、重试和费用上限的 72 指令固定模型评测。

所有模型输出都只是候选任务。服务器会先执行类型、白名单、参数、权限、工具、目标和容器检查；只有验证通过后，女仆才会回复“接受”并启动确定性执行器。模型不会直接控制逐 tick 移动、攻击或方块修改。

## 开发环境

- Minecraft Java Edition 26.1.2
- Java 25
- Fabric Loader 0.19.3
- Fabric API 0.155.2+26.1.2
- Fabric Loom 1.17.9

Windows 构建：

```powershell
.\gradlew.bat build
```

启动开发客户端：

```powershell
.\gradlew.bat runClient
```

## 游戏内命令

```text
/maid spawn
/maid list
/maid select <UUID前缀或唯一名称>
/maid follow
/maid stay
/maid home
/maid cancel
/maid name <名称>
/maid schedule day|night|all-day
/maid location set|clear work|leisure|sleep
/maid home-bound <true|false>
/maid radius <1..服务器上限>
/maid status
/maid inventory
/maid retrieve
/maid 跟着我
/maid collect minecraft:oak_log 8 16
/maid 收集 8 块橡木原木
/maid deposit minecraft:oak_log 8 16
/maid 把 8 个橡木原木放进箱子
/maid collect-and-deposit minecraft:oak_log 8 16
/maid 砍 8 个橡木原木然后放进箱子
/maid-skin upload <本地64×64 PNG路径>
/maid-skin clear
```

潜行右键 AI 女仆可打开背包与生活配置界面。物品区首格是真实主手，其余 35 格为储物；把斧头放入其中任一位置即可执行采集任务，执行时会安全借到主手并按原版消耗耐久。普通右键可喂食或把手持护甲穿到对应护甲栏，被替换的旧护甲会安全归还玩家。

`/maid-skin` 是客户端命令：本地文件先在客户端预检，再发送给服务器重新校验、剥离元数据和编码。当前自定义皮肤统一使用 Alex 瘦臂模型，不提供宽臂/瘦臂自动识别。

## 生活玩法配置

首次启动会生成 `.minecraft/config/ai-partner-gameplay.json`；开发环境对应 `run/config/ai-partner-gameplay.json`，仓库示例见 [`config/ai-partner-gameplay.example.json`](config/ai-partner-gameplay.example.json)。默认值包括每位主人最多 1 名女仆、活动半径 8（最大 32）、日落/夜晚/黎明边界、睡眠恢复、喂食好感冷却，以及物品拾取、经验拾取、内置声音和聊天气泡开关。

行为优先级固定为：打开 GUI 暂停 > 有限任务 > 跟随/待命/回家指令 > 当前日程 > 空闲漫步。只有跟随主人允许在长距离卡住后使用原版安全瞬移；回家、工作、休闲和睡眠只会寻路。

实验事件写入运行目录的 `logs/ai-partner/episodes.jsonl`。

## 可重复实验

实验命令会修改世界，因此只对游戏管理员开放。`reset` 首次在玩家东侧创建一个 21×8×21 的有界测试区；同一会话后续重置复用该锚点，只清除测试区内的方块和掉落物，不触碰区域外世界。女仆原有背包和装备会先归还玩家，玩家背包放不下时由原版逻辑掉落在玩家附近。

```text
/maid experiment list
/maid experiment reset composite_normal
/maid 砍 8 个橡木原木然后放进箱子
/maid experiment context
/maid experiment disturb
/maid experiment clear
/maid experiment batch pretest pretest-v04
/maid experiment batch resume pretest-v04
/maid experiment offline start 72 5.0 offline-v04-main
/maid experiment freeze pretest-v04
/maid experiment batch main 3 main-v04-162
/maid experiment batch variant MAID_IBC_A2_NO_RUNTIME_MONITORING 3 a2-v04-54
```

场景表冻结为 18 项，覆盖正常采集/存放/组合任务、缺目标、缺工具、背包满、不可达、缺物品、无箱子、箱子满、取消、参数边界以及目标/箱子运行中消失。只有带预设扰动的三个场景能执行 `disturb`。

以下命令会把 72 条平衡中文金标数据、Rule-BT 逐条预测和按类别汇总指标导出到 `logs/ai-partner/evaluation/`：

```text
/maid experiment export-evaluation
```

数据集版本和 SHA-256 会写入指标文件；每个类别固定 12 条，并在调参前冻结为 6 条 `dev` 与 6 条 `test`。

v0.4 的批处理会自动重置、运行和独立判定场景，并在 `logs/ai-partner/batches/<batch_id>/` 写入可恢复检查点、逐 episode 结果和汇总。完整预实验是 Rule-BT 18 场景各一次，加 LLM-Schema 与 Maid-IBC 各 5 个代表场景；冻结审计通过后，`main 3` 运行最低规模 162 episodes，`main 5` 运行理想规模 270 episodes。A2 消融可通过 `batch variant MAID_IBC_A2_NO_RUNTIME_MONITORING` 单独运行。

固定模型离线评测支持 1–72 条、12 RPM 限速、最多 3 次重试、断点恢复和美元费用上限，自动输出 JVR、意图准确率、槽位 F1、CCR、URR、FRR。详细命令、冻结门槛与产物结构见 [`docs/EVALUATION.md`](docs/EVALUATION.md)。

## LLM 配置

模组第一次启动时会生成 `.minecraft/config/ai-partner.json`；开发环境对应 `run/config/ai-partner.json`。仓库内的 [`config/ai-partner.example.json`](config/ai-partner.example.json) 是不含密钥的示例。

默认配置已使用 DeepSeek 的 OpenAI-compatible Chat Completions 端点、`deepseek-v4-flash` 模型和 `DEEPSEEK_API_KEY` 环境变量。设置环境变量后需要重启启动器/游戏，让 Minecraft 进程继承新变量。

如需改用其他 OpenAI-compatible 端点：

1. 把 `llmEnabled` 改为 `true`；
2. 填写 `endpoint` 和固定的 `model` 标识；
3. 把 API 密钥写入 `apiKeyEnvironmentVariable` 指定的环境变量；本地无鉴权端点可以不设置；
4. 如果端点不支持 `response_format: {"type":"json_object"}`，将 `requestJsonResponseFormat` 改为 `false`。

LLM 未启用、超时或输出无效时，实体会安全待命。未启用时，`/maid <message>` 自动使用内置 `Rule-BT` 解析器。

## 测试

```powershell
.\gradlew.bat test
```

当前 60 项单元测试覆盖规则/组合指令解析、严格 JobSpec JSON 边界、任务注册表、契约生命周期、UI 动作白名单、日程边界、主人索引、成长数据、皮肤校验、旧背包迁移，以及冻结实验清单与数据集。详细模块设计和状态图见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，完整场景表和评测流程见 [`docs/EVALUATION.md`](docs/EVALUATION.md)。

## 研究结果与复现

- v0.4 分析流程与统计解释见 [`analysis/README.md`](analysis/README.md)；
- 自动生成的主实验与 A2 审计见 [`analysis/generated/analysis_report.md`](analysis/generated/analysis_report.md)；
- 包含真实结果与讨论的论文实证初稿见 [`docs/THESIS_EMPIRICAL_DRAFT_ZH.md`](docs/THESIS_EMPIRICAL_DRAFT_ZH.md)；
- 面向《韩国游戏学会论文学报》的投稿差距、代码审计与阶段验收门见 [`docs/KCGS_SUBMISSION_REVIEW_ZH.md`](docs/KCGS_SUBMISSION_REVIEW_ZH.md)；
- 不覆盖 v0.4 的下一轮前瞻性实验设计见 [`docs/V0_5_PREREGISTRATION_DRAFT_ZH.md`](docs/V0_5_PREREGISTRATION_DRAFT_ZH.md)；
- 原主实验快照见 [`artifacts/experiments/v0.4-primary/`](artifacts/experiments/v0.4-primary/)，含 A2 的增量快照见 [`artifacts/experiments/v0.4-with-a2/`](artifacts/experiments/v0.4-with-a2/)。

## 下一里程碑

v0.7 将在现有生活层之上接入基础工作包：防御/近战/弓箭、农业、甘蔗/瓜类/可可、花草与除雪、蜂蜜/剪毛/挤奶、喂主人和动物、插火把与灭火。砍整棵树、挖矿、熔炉、钓鱼和完整成长系统仍属于 v0.8，不在 v0.6 中伪装为空实现。
