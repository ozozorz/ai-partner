# AI Partner 0.11 真实游戏测试报告

测试日期：2026-07-24  
目标版本：Minecraft Java Edition 26.1.2  
运行方式：Fabric Loom `runClient`，单人世界 `New World`

## 1. 构建与自动测试

执行：

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path ".gradle-user-home").Path
.\gradlew.bat compileJava compileClientJava --no-daemon
.\gradlew.bat test --no-daemon
```

结果：

- 主代码编译通过；
- 客户端代码编译通过；
- 30 个测试套件、83 个 JUnit 测试全部通过，0 失败、0 错误、0 跳过；
- 测试进程只有 JOML 对 `Unsafe` 的 JDK 弃用警告，无失败。

## 2. 客户端启动与实体创建

使用后台 Gradle 客户端启动真实 Minecraft 窗口，进入已有单人世界。

执行：

```text
/maid list
/maid spawn
/maid follow
/maid name BrainTest
```

观察：

- 未拥有女仆时 `/maid list` 给出正确提示；
- `/maid spawn` 成功生成、驯服、登记并选中新女仆；
- `/maid follow` 返回 “Okay, I will follow you.”；
- 改名后实体选择器可稳定定位 `BrainTest`；
- 日志无注册失败、Brain 为空、未注册记忆或 ticking entity 异常。

## 3. 跟随与不可达恢复

最初玩家使用相对传送进入了封闭地形，女仆保持在：

```text
[1018.6014416384535, 100.0, 1003.5]
```

这验证了 Brain 不会在没有安全位置时盲目传送。随后使用 `/spreadplayers` 把玩家放到地表安全点，并让世界持续运行 10 秒。

女仆最终位置：

```text
[1002.921968874396, 119.0, 1000.8526559167336]
```

玩家安全点中心为：

```text
[1001.5, *, 1003.5]
```

水平距离约 3 格，符合跟随停止距离。过程中没有路径或传送异常。

## 4. 驻留

执行 `/maid stay` 后记录基准位置：

```text
[1002.921968874396, 119.0, 1000.8526559167336]
```

再把玩家安全移动到以 `[1025.5, *, 1003.5]` 为中心的地表位置，持续运行 8 秒。

复查女仆位置：

```text
[1002.921968874396, 119.0, 1000.8526559167336]
```

三个坐标逐位不变，说明待命 marker 成功清理旧 `WALK_TARGET` 并停止导航。

## 5. 纯本地对话

按 `R` 打开对话框。界面显示：

```text
Offline rule parser; no network request will be sent.
```

输入：

```text
follow me
```

点击 Send 后：

- 对话框关闭；
- 聊天输出 “Okay, I will follow you.”；
- 女仆从驻留状态恢复跟随；
- 稍后位置变为 `[1021.2607584676347, 123.0, 1005.4640328917212]`，接近已移动的玩家。

此流程没有端点、密钥、模型选择或远程等待状态。

## 6. 战斗 Activity

### 6.1 无装备压力探测

把难度临时改为 Normal，生成普通僵尸，并用带来源的 `/damage` 让僵尸成为自卫来源。

无装备、1 级、20 最大生命的女仆与普通僵尸交战后被击杀；随后按标签查询僵尸也已不存在。这个探测说明目标获取和近战确实启动，但也暴露出无装备低等级女仆面对完整生命普通僵尸存在同归于尽风险。

该结果属于战斗平衡风险，不作为功能通过依据。

### 6.2 装备后的可控验证

重新生成并命名 `CombatTest`，为主手装备铁剑。第一次对移动僵尸触发自卫后：

- 女仆生命为 `19.0`，只受到测试命令的 1 点伤害；
- 僵尸生命从 `20.0` 降为 `13.0`，与铁剑一次 7 点伤害一致。

随后清除旧战斗目标，设置夜间，生成带 `NoAI` 的固定僵尸，重新触发自卫并运行 5 秒。

结果：

- `@e[tag=BrainTarget3]` 查询返回 “No entity was found”；
- `CombatTest` 仍存活；
- 状态从临时战斗恢复为 `FOLLOWING`；
- 成长状态变为 `XP 4`、好感 `2`，证明击杀回调实际发生；
- 铁剑仍由女仆持有，装备租用没有丢失物品。

这覆盖了威胁传感器、`ATTACK_TARGET`、FIGHT Activity、移动/注视目标、近战范围、攻击冷却、真实伤害和 Activity 退出恢复。

## 7. Brain NBT

执行：

```text
/data get entity @e[type=ai-partner:ai_partner,name=CombatTest,limit=1] Brain
```

结果：

```text
{memories: {}}
```

这是预期结果：女仆专用 marker 和当前移动/战斗意图都是没有 Codec 的瞬时记忆，不写入存档。手动指令、任务、日程和战斗策略由各自权威控制器保存，重载后传感器会重建 Brain 状态。

## 8. 日志检查与退出

检查 `run/logs/latest.log`：

- 无 `ticking entity`；
- 无未注册记忆；
- 无 Brain 创建或 Codec 异常；
- 无模组线程异常；
- 仅出现开发启动器预期的 Mojang/Realms 认证错误，不影响本地单人测试。

测试结束后：

- 难度恢复为 Peaceful；
- `pauseOnLostFocus` 恢复为 `true`；
- 使用 “Save and Quit to Title” 正常保存世界；
- 从主菜单正常退出客户端；
- Java Minecraft 窗口和 Gradle 客户端进程正常结束。

## 9. 尚需长期回归的场景

本次真实运行已经覆盖核心重构路径，但以下场景仍适合后续 GameTest 或人工长测：

- 多人同时选择和操作同一女仆；
- 区块卸载、重载和跨重启任务恢复；
- 长距离移动目标持续变化；
- 门、围栏、水体、矿洞和复杂高差的组合寻路；
- 有弓和箭时的远程攻击、弹道和装备归还；
- 多女仆同时争用工作台、熔炉和容器；
- 数小时日程切换与睡眠床位竞争。
