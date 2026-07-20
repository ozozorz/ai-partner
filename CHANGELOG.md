# Changelog

## 0.2.0 - 2026-07-21

- 修正 Alex 皮肤资源路径，并补全瘦臂护甲层和双手物品渲染；
- 将女仆储物区扩展为 36 格，接入四格原生护甲槽和副手槽；
- 添加潜行右键背包 UI、状态面板、跟随/待命/取消按钮和完整物品移动；
- 支持手持护甲普通右键直接穿戴，并安全归还被替换的旧护甲；
- 默认接入 `DEEPSEEK_API_KEY`、DeepSeek V4 Flash 和 JSON 输出；
- 参考 TouhouLittleMaid 的核心/任务行为分层，加入空闲漫步、开关门和更自然的延迟传送跟随。

## 0.1.0 - 2026-07-20

- 搭建 Minecraft 26.1.2、Java 25、Fabric Loader 0.19.3 工程；
- 添加使用官方 Alex 模型与贴图的可绑定女仆实体；
- 实现 `FOLLOW`、`STAY`、`COLLECT_BLOCK`、`DEPOSIT_ITEM` 和 `CANCEL`；
- 添加 TaskDefinition 注册表、IBC 编译器、目标谓词、有限重试和类型化失败码；
- 添加异步 OpenAI-compatible LLM 网关、严格 JobSpec Schema 和本地取消通道；
- 添加异步 JSONL 实验日志和服务器停止时落盘；
- 添加规则解析、JobSpec、任务注册表和契约生命周期单元测试。
