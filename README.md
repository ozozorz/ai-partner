# AI Partner

AI Partner 是一个面向 Minecraft Java Edition 26.1.2 的 Fabric 模组原型。它使用“指令—行为契约（Instruction–Behavior Contract, IBC）”把玩家指令转换为受约束、可验证的伙伴行为。

当前版本为第二个可运行里程碑：

- 自定义 AI 女仆实体，暂时复用 Minecraft 官方 Alex 模型和皮肤；
- 36 格女仆背包、四格护甲栏、副手栏以及可移动物品的容器 UI；
- UI 同步显示生命、模式、任务和契约状态，并提供跟随、待命、取消按钮；
- `/maid spawn` 生成并绑定单个主人；
- `FOLLOW`、`STAY`、`COLLECT_BLOCK`、`DEPOSIT_ITEM`、`CANCEL` 显式状态机；
- `/maid <message>` 的中英文规则解析降级路径；
- 执行前权限、维度和参数验证；
- 有限寻路重试、类型化失败状态；
- 异步 JSONL 契约事件日志。

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
```

潜行右键 AI 女仆可直接打开背包界面；把斧头拖入 36 格储物区后即可执行收集任务。普通右键可把手持护甲直接穿到对应护甲栏，被替换的旧护甲会安全归还玩家。

实验事件写入运行目录的 `logs/ai-partner/episodes.jsonl`。

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

当前测试覆盖规则解析、严格 JobSpec JSON 边界、TaskDefinition 注册表、契约生命周期和 UI 行为按钮白名单。详细模块设计见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 下一里程碑

- 固定两阶段 `COLLECT_AND_DEPOSIT` 编排；
- 可重置游戏测试场景与离线评测数据集。
