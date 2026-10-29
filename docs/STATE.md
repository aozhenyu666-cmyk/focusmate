# STATE

> 阶段 2 已落代码并通过本地验证；真机验收 A–J 全部 **NOT_RUN**（`adb devices` 为空）。

## 当前阶段/任务

阶段 1（可靠限制与恢复）。
- 任务 1.1 最小工程 ✅（可构建、可出 APK）
- 任务 1.2 限制状态与实际结果 ✅（状态机 + 全程核验 + 失败可表达）
- 任务 1.3 到期与故障恢复 ✅（本地解除 + 闹钟 + 开机恢复决策）
- 任务 1.4 真机验收 ⛔ **NOT_RUN**（无设备在线）

## 代码版本

- 工程：`C:\focusmate`（工作区内 junction 可见为 `default-workspace\focusmate`）
- 应用 ID：`com.focusmate`　版本：`0.1.0-phase1` / versionCode 1
- 工具链：AGP 8.7.3、Kotlin 2.0.21、Gradle 8.11.1、JDK 21、compileSdk/targetSdk 35、minSdk 30
- APK：`app/build/outputs/apk/debug/app-debug.apk`（23,202,250 bytes，debug 签名）

## 已完成并已观察到的结果

| 项 | 结果 | 证据 |
|---|---|---|
| 编译 | 通过 | `:app:compileDebugKotlin` |
| JVM 单测 | **52 passed / 0 failed** | `app/build/test-results/testDebugUnitTest/*.xml` |
| lint | **0 errors / 7 warnings** | `app/build/reports/lint-results-debug.txt` |
| debug APK | 23,202,250 bytes | `app/build/outputs/apk/debug/` |
| 一键构建脚本 | `build.ps1`（含结果汇总与 APK 哈希） | 已跑通 |
| 远端仓库 | https://github.com/aozhenyu666-cmyk/focusmate （public） | 本地与远端同为 `997724c` |
| 网络 | `git push` 间歇性失败（TCP 握手不完成），非配置问题 | 实测见 `docs/NETWORK.md` |

单测分布：
- `FocusGuardTest` 18：状态迁移、拦截/收起、微信与通话放行、系统界面中性、到期本地解除、
  解除核验、终态抗迟到回调、重复开始被拒、重复拦截去重、无包名、非正时长。
- `ModelTest` 9：序列化往返、枚举稳定字符串、未知字段兼容、状态集合不重叠、
  解除中不接受新事件、规则版本可失效旧结果、自有记录默认为空。
- `RecoveryTest` 8：无会话、读坏要告知、终态只清理、过期立即解除、恰好到期、
  未过期退回 PREPARING、失败/用户结束不被复活。
- `AllowPolicyTest` 7：四类判定、空包名、输入法中性、未知不默认放行。
- `SessionStoreTest` 5：读写往返、无文件、损坏留痕、单份写入、clear。
- `TimeAndPresetsTest` 5：10 分钟时长、微信在/不在、改系统时间不改截止、重启按墙钟、单应用预设。

## 尚未通过 / 尚未测试

- **真机行为全部 NOT_RUN**：`adb devices` 为空。
- 未验证：无障碍能否连上、`rootInActiveWindow` 能否读到前台包、
  `TYPE_ACCESSIBILITY_OVERLAY` 在本机能否 addView 成功、覆盖层是否吞返回键、
  启动 Gemini 是否被厂商系统拒绝、息屏下到期闹钟是否准时、强行停止后重开是否按预期恢复。
- 未实现：Shizuku、挂起应用、锁屏、对话与主动交流（阶段 2）、复盘记忆（阶段 3）。

## 下一个动作

1. 用户回答 Q-03（微信放行粒度）与 Q-04（到期是否直接放开），这两条决定 D-07 的验收口径。
2. 接上真机后按 `docs/ACCEPTANCE.md` A-01～A-12 逐条跑并回填。
3. Q-01/Q-02 有答复后调整 D-04/D-06 并重跑受影响用例。


---

# 阶段 2 状态（2026-10-29 更新）

## 当前阶段/任务

阶段 2（依据 dsh-world 与 SurfCore 实现主动伙伴）。
- 任务 2.1 人格与最小记忆 ✅ 代码完成，JVM 验证通过
- 任务 2.2 主动事项与调度 ✅ 代码完成，JVM 验证通过
- 任务 2.3 输出与回应 ✅ 代码完成，JVM 验证通过（TTS 只做了接口与失败退回，未真机听）
- 任务 2.4 验收与回归 ⛔ 真机全部 **NOT_RUN**

## 本轮新增文件

```
app/src/main/java/com/focusmate/companion/
  Persona.kt              稳定人格（临时值，DECISIONS Q-06 未确认）
  Fact.kt                 事实模型：三种来源 + 三种状态 + 只读 label()
  Dialogue.kt             有界对话日志
  FollowUp.kt             事项模型：来源/依据/话题/到期/状态/版本
  CompanionConfig.kt      主动规则（保守默认）
  SchedulePolicy.kt       判定与排程（纯函数）
  FollowUpEngine.kt       创建/替换/改期/取消/投递/失败/过期
  FactEngine.kt           评分/去重/复习/更正/遗忘/召回
  CompanionContext.kt     唯一上下文入口
  IntentParser.kt         改期/取消/改锁定的确定性解析
  ModelClient.kt          模型端口 + 脱敏描述
  OpenAiCompatibleClient.kt  /chat/completions 最小客户端
  DeliveryLog.kt          DeliveryTrace：生成/展示/TTS 五步留痕
  DeliveryGate.kt         生成前后版本校验 + 迟到上限
  SpeechOutput.kt         系统 TTS，单段串行，失败只影响语音
  CompanionRuntime.kt     唯一协调点（对话、tick、投递、串行化）
  CompanionService.kt     前台服务 60 秒 tick
  CompanionHolder.kt      单例，避免两个运行时写同一份状态
  ModelSettings.kt        API Key 本地存放（不入库、不入日志）
app/src/main/java/com/focusmate/ui/CompanionScreen.kt  对话/记忆/事项/设置四个页签
app/src/test/java/com/focusmate/companion/ 5 个测试类共 70 项
```

## 已验证（本地）

| 项 | 结果 | 证据 |
|---|---|---|
| 编译 | 通过 | `:app:compileDebugKotlin` |
| JVM 单测 | **133 passed / 0 failed** | `app/build/test-results/testDebugUnitTest` |
| lint | **0 errors / 10 warnings** | `app/build/reports/lint-results-debug.txt` |
| debug APK | 23,834,266 bytes | SHA256 `9CE055B07D700B16B7BBEB075EA966369633D09A3751E2F41217B64B1010F3B7` |

阶段 2 新增的 81 项单测分布（原 72 + 云端复测整改 9）：
- `FactEngineTest` 16：三种来源可区分、模型推测被压权、去重窗口、复习、更正退出上下文、
  遗忘留墓碑、墓碑不被重新激活、召回排序与上限、分词。
- `FollowUpEngineTest` 17：约定创建、同话题替换、依据必填、人格兴趣去重、改期、
  取消、投递串行（DELIVERING 不可再取）、版本不符丢弃、正常完成、失败退避、
  超限转 FAILED、过期清理、dueNow 选择。
- `SchedulePolicyTest` 17：跨零点免打扰、起止相同不启用、非跨零点区间、判定顺序
  （disabled/quiet/daily-max/waiting/no-topic/user-active/run）、首次排程、
  停机只补一枪、落在免打扰被推后、达到上限推到次日。
- `IntentParserTest` 10：十分钟/半小时/两小时/等下再聊、取消、改锁定被识别为拒绝、
  普通聊天不产生意图、依据必填、中文数字 1..99。
- `FlowTest` 12：A 到点开口、B 改期后旧事项失效且接续同话题、C 生成期取消结果被拦、
  D 回应后关闭待跟进、E 更正后上下文无旧事实、F 失败退避不轰炸、G 序列化往返、
  I 与专注会话互不引用、J 闲聊必须有依据、迟到丢弃、窗口内允许、上下文含人格与依据。

## 尚未通过 / 尚未测试

**真机 A–J 全部 NOT_RUN**（无设备）。未验证的具体行为：
- 前台服务能否在息屏/省电下持续 tick，迟到多久；
- 通知能否实际弹出（Android 13+ 需要 POST_NOTIFICATIONS 授权，代码里申请了权限但未在真机授权过）；
- TTS 是否真的发声、失败是否真的退回文字（代码路径已写，未听）；
- 模型真实调用（未配置 Key，且 Q-06/Q-07 未确认服务商）；
- 进程重建后事项与事实是否原样存在；
- 与阶段 1 的共存：专注进行中时伙伴是否会插话（当前 `SchedulePolicy` 没有"专注中禁言"的输入，
  **这是一个已知缺口**，见下方"下一步"第 2 条）。

## 已知缺口（不掩盖）

1. **专注中可能插话**：`SchedulePolicy.evaluate` 没有接收"是否在专注"的入参，
   所以专注进行中伙伴仍可能主动开口。执行指令 §7 任务 2.3 要求按确认规则处理"专注中发言"，
   而该规则本身还没被用户确认（Q-05）。当前如实记录为缺口，不假装已处理。
2. **主动闲聊默认关闭**：`allowSmallTalk = false`，所以 A–J 里的 J 用例
   （非显式闹钟触发的开场）在默认配置下不会发生，只能在设置里打开后验证。
3. **墓碑不是物理删除**（D-15）。
4. **UI 里没有"更正/忘记"按钮**：`FactEngine.correct/forget` 已有且被单测覆盖，
   但界面只读，改正是通过对话触发（说"改成……"目前不会被自动识别为更正指令）。
   **这是本轮真实缺口**：任务 2.1 的验收项"更正事实后，后续回答使用新信息"
   目前只有引擎层证据，没有 UI 入口。

## 下一步

1. 等 Q-08（文字+TTS 是否可接受）、Q-09（是否要主动闲聊）、Q-05（频率/免打扰）
   与 Q-06（角色称呼/风格/模型服务商）的答复，再调整默认值与人格。
2. **补"专注中禁言"**：把 `GuardRuntime.session()` 的进行中状态接进 `SchedulePolicy`，
   专注期间返回 `Wait`。这是执行指令任务 2.3 的明确要求，当前缺失。
3. **补"更正/忘记"的 UI 入口**（记忆页每条加两个按钮），把引擎能力变成可用行为。
4. 接上真机后按 `docs/ACCEPTANCE.md` 阶段 2 表 A–J 逐条跑。


---

# 云端复测整改（2026-10-29）

| 编号 | 问题 | 状态 |
|---|---|---|
| F-01 | APK 缺 INTERNET | 已修，`aapt2 dump permissions` 确认合并后 APK 含该权限 |
| F-02 | 遗忘/更正后原句仍进模型输入 | 已修，`Utterance.hidden` + `forgetWithSource`/`correctWithSource` |
| F-03 | 无关回复清空未来约定 | 已修，新增 `AWAITING_REPLY`，只结算该状态 |
| F-04 | 迟到失败复活已取消事项 | 已修，`markFailed` 带 `issuedVersion` + DELIVERING 守卫 |
| F-05 | 生成期记忆变化仍会发出 | 已修，`memoryVersion` + 输出前复算是否允许开口 |
| 缺口1 | 闲聊开关无 UI | 已加开关与 `setAllowSmallTalk` |
| 缺口2 | 伙伴无自己的唤醒链路 | 已加 `CompanionScheduler` + `CompanionWakeReceiver`，BootReceiver 一并恢复 |

新增 `app/src/test/java/com/focusmate/companion/CloudRegressionTest.kt`（9 项）。
每条修复都用「临时改回旧行为 -> 用例必须失败 -> 改回 -> 用例通过」验证过，
证明这些用例不是空断言。记录见 ACCEPTANCE「反向验证」列。

**仍待设备验证**：F-01 只证明权限存在，不证明请求成功；UI/通知/TTS/后台/真实模型全部 NOT_RUN。
