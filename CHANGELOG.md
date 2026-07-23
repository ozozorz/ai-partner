# Changelog

## 0.11.0 - 2026-07-24

### 破坏性变更

- 删除全部 LLM 网关、请求结果、提示词、Schema、端点策略、API Key 配置、逐女仆驱动模式、远程对话记忆和多步模型工作流；
- 删除 `/maid driver` 命令、相关网络载荷、配置示例、依赖和测试；
- 删除五个基于 `Goal` 的女仆自主移动/战斗实现，女仆不再注册自主 `GoalSelector` 目标。

### Brain AI

- 新增女仆专用 `Brain.Provider`、八种瞬时记忆模块和两种传感器；
- 使用 `CORE`、`FIGHT`、`WORK`、`REST`、`IDLE` Activity 仲裁自主行为；
- 接入原版 `HURT_BY`、`NEAREST_LIVING_ENTITIES`、`LOOK_TARGET`、`WALK_TARGET`、`ATTACK_TARGET`、`ATTACK_COOLING_DOWN` 和 `CANT_REACH_WALK_TARGET_SINCE` 记忆；
- 跟随加入 5/3 格启停滞回、原版不可达计时、12 格安全传送门槛、有限重试和失败终态；
- 回家、日程位置、休息和普通移动改为 Behavior 写目标、原版 Sink 推进导航；
- 战斗改为威胁传感器选目标、Activity 验证、接近、视野检查、冷却和装备租用；
- GUI 暂停、任务切换、手动指令切换和 Activity 退出统一清理过期移动意图。

### 本地交互

- R 键对话框改为纯本地规则解析，并明确显示不会发送网络请求；
- 本地解析只产生一个类型化意图，统一经过 `MaidControlService` 和 `MaidActionRegistry` 校验；
- 保留两分钟澄清上下文、目标女仆解析、社交回应和安全拒绝。

### 验证与文档

- Java 主代码、客户端代码和全部 JUnit 测试通过；
- 在真实 Minecraft 26.1.2 开发客户端验证生成/绑定、跟随、驻留、本地对话、近战、自卫清理和 Brain NBT；
- 重写 README 与架构文档，新增 Brain AI 机制说明和真实游戏测试报告；
- 删除过期的远程模型工作流与旧项目 Review 文档。

## 0.10.0 - 2026-07-22

- 完成类型化语义动作、有限任务契约、持续工作、生活日程、成长、皮肤和服务端权威 GUI；
- 该版本曾包含远程模型实验入口，已在 `0.11.0` 完整移除。
