# Minecraft 26.1.2 生物 AI 与女仆实现

本文是三篇 Minecraft 生物 AI 机制文章与 26.1.2 源码的学习整理，并说明这些机制如何落到 AI Partner。

参考文章：

1. [Minecraft 生物 AI 机制详解（一）](https://b23.tv/7VQhyW4)
2. [Minecraft 生物 AI 机制详解（二）](https://b23.tv/BIwK20d)
3. [Minecraft 生物 AI 机制详解（三）](https://b23.tv/mfkbCA3)

## 1. 两套上层组织方式

Minecraft 生物 AI 不是单一算法。常见上层组织有：

- `GoalSelector`：按优先级、控制标志和 `canUse` / `canContinueToUse` 管理 Goal；
- `Brain`：传感器写记忆，Activity 按记忆条件启停，Behavior 读取和修改记忆。

两者下方仍共享移动控制、注视控制、跳跃控制、导航和寻路。区别主要在“状态如何表达”和“行为如何仲裁”。

Goal 适合局部、独立的行为，但多个 Goal 往往通过实体字段、目标字段和导航副作用耦合。Brain 把“看见了什么”“想去哪里”“正在攻击谁”“为什么不可达”放进有类型的记忆槽，并允许 Activity 在退出时统一清理。

AI Partner 选择 Brain 作为女仆自主 AI 的唯一上层组织方式；空的 `registerGoals()` 明确防止旧 Goal 重新接管导航。

## 2. Brain 的真实 tick 顺序

Minecraft 26.1.2 `Brain.tick` 的源码顺序是：

```java
forgetOutdatedMemories();
tickSensors(level, body);
startEachNonRunningBehavior(level, body);
tickEachRunningBehavior(level, body);
```

这带来几个重要结论：

1. 有时限的冷却记忆会在行为判断前更新；
2. 传感器是当 tick 的事实生产者；
3. 只有当前激活 Activity 中的 Behavior 可以启动；
4. 运行中的 Behavior 仍需在每 tick 通过持续条件，否则会停止；
5. 行为退出时应清理自己拥有的短期状态，Activity 切换还可批量清理记忆。

女仆在 Brain tick 后调用 `setActiveActivityToFirstValid`。最新传感结果因此决定下一 tick 的非核心 Activity，形成稳定且容易推理的单 tick 边界。

## 3. 记忆不是普通字段

`MemoryModuleType<T>` 同时表达：

- 值的类型；
- 这个槽是否注册；
- 行为需要“存在”“不存在”还是“只需注册”；
- 值是否有过期时间；
- 是否存在 Codec、能否序列化。

行为声明所需记忆后，Brain 在加入 Activity 时会自动注册这些槽。传感器也通过 `requires()` 声明其写入槽。

女仆专用 marker 不带 Codec，因为 `FOLLOW_OWNER`、`PAUSED`、`TASK_CONTROLLED` 等都是权威控制器状态的投影。如果把它们保存到 NBT，就会在重载后产生“存档说正在跟随，但主人/维度/任务已经变了”的双重真相。正确做法是载入后由传感器重建。

## 4. Sensor：从世界事实到短期记忆

原版传感器通常低频扫描昂贵事实，高频行为只读取记忆。女仆采用同样的分工：

- `HURT_BY` 记录受击来源；
- `NEAREST_LIVING_ENTITIES` 提供附近和可见实体；
- `MaidThreatSensor` 每 5 tick 从受击记录与主人战斗记录中选择合法目标；
- `MaidStateSensor` 每 tick 把指令、任务、日程、GUI 和活动地点投影成 marker。

自定义威胁传感器不扫描“所有可攻击实体”。这避免自卫策略退化成主动屠杀，也让战斗目标来源可以解释。

## 5. Activity：一组行为加一组成立条件

26.1.2 的 `ActivityData` 包含：

- Activity 类型；
- 行为与优先级对；
- 进入所需的记忆状态；
- 停止 Activity 时要擦除的记忆。

Brain 同时激活所有 CORE Activity 和至多一个非核心 Activity。女仆使用：

| Activity | 条件 | 作用 |
|---|---|---|
| `CORE` | 始终 | 暂停、游泳、注视汇接、移动汇接、开门 |
| `FIGHT` | 有合法攻击目标且未暂停 | 目标验证、接近、远程/近战 |
| `WORK` | 日程为工作且未暂停 | 前往工作地点和工作空闲行为 |
| `REST` | 日程为睡眠且未暂停 | 前往睡眠地点、等待或休息 |
| `IDLE` | 默认 | 跟随、待命、回家、休闲和闲逛 |

优先选择 `FIGHT → WORK → REST → IDLE`。Activity 退出清理是防止“战斗结束后仍追着旧路径走”的关键。

## 6. Behavior：声明条件，写入意图

`BehaviorBuilder` 可以把多个记忆条件组合为一个类型安全行为。女仆行为遵循一个约束：

> 自主高层行为只表达意图，不直接拥有长期导航。

例如跟随行为只做：

1. 检查跟随、暂停和任务 marker；
2. 获取主人；
3. 写 `LOOK_TARGET`；
4. 根据 5/3 格滞回决定是否写 `WALK_TARGET`；
5. 读取不可达计时决定是否尝试安全传送。

真正的路径创建和移动由 CORE 中的 `MoveToTargetSink` 完成。

## 7. 从 WalkTarget 到 A* 路径

文章第三篇强调了路径节点、节点类型、代价和 malus。26.1.2 的实际调用链可概括为：

```text
WalkTarget
  → MoveToTargetSink
  → PathNavigation.createPath
  → PathFinder.findPath
  → NodeEvaluator.getNeighbors
  → Path
  → PathNavigation.followThePath
```

`NodeEvaluator` 把世界空间离散为可搜索节点，并根据实体尺寸、方块、流体、门、危险和路径类型决定节点是否可用。`PathFinder` 在这些节点上做启发式搜索；`Path` 记录节点序列以及是否真正抵达目标。

“创建了 Path”不等于“能到达目标”。`MoveToTargetSink.tryComputePath` 会检查 `path.canReach()`：

- 能到达：擦除不可达记忆；
- 不能到达：首次设置 `CANT_REACH_WALK_TARGET_SINCE`；
- 连部分路径也没有：尝试朝目标找一个局部中间点。

这正是女仆不应自己维护另一套“卡住计时器”的原因。项目直接消费原版不可达记忆，只在上层增加跟随传送策略和契约失败语义。

## 8. 为什么跟随需要滞回

如果“超过 4 格开始走、低于 4 格停止”，主人和女仆在边界附近的小幅移动会导致每 tick 反复创建和删除目标。

当前实现：

- 距离大于 5 格进入移动状态；
- 距离不大于 3 格退出移动状态；
- 3–5 格保持上一次状态。

这个滞回减少路径重算、动画抖动和导航启停。

## 9. 安全传送不是通用寻路替代

驯服生物跟随主人时，跨沟壑、门或区块边缘可能长期不可达。女仆只在以下条件同时满足时调用原版安全传送：

- 当前是跟随主人；
- 距离至少 12 格；
- 原版不可达记忆已持续 60 tick；
- 没有 GUI 暂停或有限任务接管；
- 原版传送位置检查通过。

战斗、工作、休闲、睡眠和回家不允许传送。它们的目标是世界语义的一部分，用传送绕过失败会破坏活动半径、危险检查和任务证据。

## 10. 战斗也是记忆行为

战斗链路是：

```text
受击/主人受击
  → HURT_BY_ENTITY
  → MaidThreatSensor
  → ATTACK_TARGET
  → FIGHT Activity
  → 目标验证
  → LOOK_TARGET / WALK_TARGET
  → 可见性与距离判断
  → ATTACK_COOLING_DOWN
```

近战和远程行为都要求目标出现在 `NEAREST_VISIBLE_LIVING_ENTITIES` 中。攻击成功后写带 TTL 的冷却记忆，避免用每 tick 直接攻击模拟异常攻速。

战斗控制器只保留：

- 合法目标规则；
- 近战/远程策略；
- 武器与箭检查；
- 单次真实攻击原语；
- 临时装备租用。

目标生命周期和移动生命周期属于 Brain。

## 11. 有限任务为什么不全部改成 Behavior

采集一组方块、精确存入若干物品、递归制作工具、租用熔炉等行为需要：

- 可持久化阶段；
- 数量和物品守恒；
- 区块与容器复验；
- 可恢复的中断点；
- 明确成功或失败证据。

把这些全部拆成无状态 OneShot 会丢失事务边界。因此项目采用混合分层：

- Brain 仲裁自主活动和共享移动意图；
- 有限任务状态机拥有确定性世界动作；
- `TASK_CONTROLLED` 让 Brain 在任务期间让出移动控制权；
- 战斗可临时中断任务，但不销毁任务快照。

这不是两套 AI 互相竞争，而是上层自主仲裁与下层事务执行的职责分离。

## 12. 对照过的 26.1.2 源码

主要对照类：

- `LivingEntity`：Brain 创建、保存与实体生命周期；
- `Brain`：记忆、传感器、Activity、行为启动和 tick 顺序；
- `ActivityData`：行为优先级、进入条件和退出清理；
- `Behavior`、`OneShot`、`BehaviorBuilder`：行为生命周期与声明式记忆条件；
- `Sensor`、`SensorType`：扫描周期和所需记忆；
- `MoveToTargetSink`：路径创建、不可达记忆、局部中间点和重试；
- `LookAtTargetSink`：注视目标汇接；
- `PathNavigation`、`GroundPathNavigation`：路径缓存、跟随和卡住判断；
- `PathFinder`、`NodeEvaluator`、`WalkNodeEvaluator`：节点搜索和地形分类；
- 原版驯服生物与 Brain 生物：主人跟随、安全传送和 Activity 组织方式。

项目实现细节与类清单见 [架构文档](ARCHITECTURE.md)。

