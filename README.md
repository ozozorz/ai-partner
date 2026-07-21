# AI Partner

AI Partner 是一个面向 Minecraft Java Edition 26.1.2 的 Fabric 模组原型。它使用“指令—行为契约（Instruction–Behavior Contract, IBC）”把玩家指令转换为受约束、可验证的伙伴行为。

当前版本为 v0.4 实验闭环里程碑：

- 自定义 AI 女仆实体，暂时复用 Minecraft 官方 Alex 模型和皮肤；
- 36 格女仆背包、四格护甲栏、副手栏以及可移动物品的容器 UI；
- UI 同步显示生命、模式、任务和契约状态，并提供跟随、待命、取消按钮；
- `/maid spawn` 生成并绑定单个主人；
- `FOLLOW`、`STAY`、`COLLECT_BLOCK`、`DEPOSIT_ITEM`、`COLLECT_AND_DEPOSIT`、`CANCEL` 显式状态机；
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
/maid follow
/maid stay
/maid cancel
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
```

潜行右键 AI 女仆可直接打开背包界面；把斧头拖入 36 格储物区后即可执行收集任务。普通右键可把手持护甲直接穿到对应护甲栏，被替换的旧护甲会安全归还玩家。

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

当前测试覆盖规则/组合指令解析、严格 JobSpec JSON 边界、TaskDefinition 注册表、契约生命周期、UI 行为按钮白名单、18 个场景清单和 72 条冻结数据集。详细模块设计见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，完整场景表和评测流程见 [`docs/EVALUATION.md`](docs/EVALUATION.md)。

## 下一里程碑

- 添加客户端 UI 与 Alex 渲染截图回归；
- 在更多地图种子与服务器负载下扩展外部效度验证。
