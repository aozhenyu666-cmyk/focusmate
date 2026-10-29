# SOURCES

固定提交用于重现本次研究。**未直接复制任何上游代码**：本项目全部为按机制重写，
因此不存在 GPL 传染问题；若后续确实复制 DetoxDroid 代码，必须按 GPL-3.0 处理分发与声明。

## S1 DetoxDroid —— 锁定三档与恢复

- 仓库：https://github.com/flxapps/DetoxDroid
- SHA：`f14d2352c1b66a05508253be40dfa478b794d0d4`
- 实际阅读文件：
  - `app/src/main/java/com/flx_apps/digitaldetox/features/DisableAppsFeature.kt`
  - `app/src/main/java/com/flx_apps/digitaldetox/system_integration/AppSuspender.kt`
  - `app/src/main/java/com/flx_apps/digitaldetox/system_integration/PullBrakeActivity.kt`
- 读到的事实：
  - `DisableAppsMode` 三档：`DEACTIVATE`（要设备所有者）、`BLOCK`（要悬浮窗）、`SUSPEND`（要 Shizuku）。
  - `blockedApps()` 只在无障碍服务实例存在、feature 激活、且未被 PauseButton 暂停时才返回非空集合——
    即"没有无障碍服务就等于不守护"，这一点本项目用显式 `FAILED` 表达，而不是静默返回空集。
  - `mayHoldBack()` 在锁屏或通话中（`AudioManager.MODE_NORMAL` 之外）一律不拦——
    本项目采纳其"来电绝不拦"的结论，落在 `Presets.CALL_PACKAGES` 与覆盖层出口设计上。
  - `hiddenApps` 持久化，重启后仍保持隐藏，必须重新找回才能恢复——体现"挂起可能比进程活得久"，
    因此本项目把"已挂上的限制"单独记在 `OwnedRestrictions`，只解除自己挂的。
- 保留的行为：三档差异的判定依据、来电/锁屏豁免、自有记录单列。
- Android 改动：不引入 Shizuku 与 DevicePolicy；BLOCK 改用 `TYPE_ACCESSIBILITY_OVERLAY`。
- 许可：GPL-3.0（仅阅读，未复制代码）。
- 对应验收：A-03、A-05、A-07、A-09。

## S2 Shizuku —— 一期未接入

- 仓库：https://github.com/RikkaApps/Shizuku ／ https://github.com/RikkaApps/Shizuku-API
- SHA：`b844bc491f1790c72328e1a8e5b2349f8978f0ea`
- 状态：**未阅读、未接入**。原因：用户未授权安装 Shizuku，且 D-06 选定的路径不需要它。
  不据此宣称任何 Shizuku 能力。

## S3 dsh-world —— 持久闹钟与投递串行化（阶段 2 主要原型）

- 仓库：https://github.com/chenkezhen480/dsh-world
- SHA：`27b386fd3727c440d5eb0109f2b9f76d91dc0303`
- 实际阅读文件：`src/life/runtime.ts`（18,029 字符）、`src/life/alarmTimer.ts`、`src/life/lifeStore.ts`
- 读到的事实：
  - `nextJob()` 在 greeting / review / alarm 三者里取最早的一个执行——单条待办，不排队堆积。
  - `deliver()` 用 `delivery ??=` 保证**同一时刻只有一次投递**，finally 里置空。
  - `setAlarm()` 新闹钟直接替换旧闹钟，旧的退避与故障记录一并清除——"新约定替换旧约定"。
  - `wake()` 记录 `scheduledAt / deliveredAt / lateMs`，迟到要留痕而不是假装准时。
- 一期用法：本项目只借用"单条待办 + 串行投递 + 迟到留痕"三个机制到到期调度；
  Node 定时器、DSH 会话接口不照搬（`ExpiryScheduler` 用 AlarmManager + 前台服务 tick）。
- 阶段 2 用法：人格与主动事项调度按此机制改写为 Android 持久存储版本。
- 许可：MIT（已读自有核心）。

## S4 SurfCore —— 人格、目的与记忆（阶段 2 原型，一期未用）

- 仓库：https://github.com/Mieksido/surfcore
- SHA：`d7f6b33d57b1667508c48206cbcc455815d6d336`
- 实际阅读文件：`src/character-persona.js`、`src/purpose-engine.js`、`src/memory-card.js`
- 状态：已下载到本地 `docs/_sources/`（不入库，见 .gitignore），**一期未接入任何一行**。
  阶段 2 才移植"固定人格 + 带依据的待跟进事项 + 发言理由 + 版本失效判断"。
- 许可：MIT（已读自有核心）。第三方文件的具体版本仍需在阶段 2 复核。

## S5 AIRI —— 输出调度（一期未用）

- 仓库：https://github.com/moeru-ai/airi　SHA：`689b18e2b57be4557562e35a68d84f0e3a86daed`
- 状态：未阅读。本项目一期只需要"一次只有一条投递"，已由 D-06 的实现满足，不引入 Web 运行时。

## S6 旧项目

- 仓库：https://github.com/aozhenyu666-cmyk/test
- 本地可用材料：`_pet-reports/status-20261025/C3_FOCUS_GUARD_STATE.md`（C3 专注守护记录）、
  `_pet-reports/status-20261025/STATUS_AND_NEXT_STEPS.md`（F-01～F-08 审计结论）、
  `_pet-reports/capability-assessment/CAPABILITY_ASSESSMENT.md`（能力考评）。
- 用途：F-01～F-08 八个缺陷全部转成本项目的设计约束，对应关系见 `DECISIONS.md` D-09。
- 旧 C3 源码本身未在当前工作区落盘，因此**没有逐行审查**，只依据上述三份记录。不宣称审查过其源码。


---

# 阶段 2：上游文件 → 本地实现 → 保留行为 → 验证用例

阶段 2 读的具体文件（固定 SHA），全部逐文件读过，不是只看 README：

## dsh-world @ `27b386fd3727c440d5eb0109f2b9f76d91dc0303`

| 上游文件 | 本地实现 | 保留的行为 | 验证用例 |
|---|---|---|---|
| `src/life/runtime.ts` | `companion/CompanionRuntime.kt` | 单条待办优先（`nextJob`）；投递串行化（`delivery ??=`）；先清 pending 再 steer，避免"发了又发"；失败不回滚成成功 | `FollowUpEngineTest.beginDelivery…`、`FlowTest.C` |
| `src/life/alarmTimer.ts` | `companion/SchedulePolicy.planNext` + `CompanionService` tick | 退避序列 1/5/15/30/60 分钟、`SEGMENT_MS` 分段重查墙钟、新闹钟或投递成功都重置 backoff | `FollowUpEngineTest.投递失败按重试间隔…`、`SchedulePolicyTest.停机很久后只补一枪` |
| `src/life/alarm.ts` | `companion/SchedulePolicy` + `IntentParser` | 只接受 `at` 或 `after_minutes` 之一；最短 1 分钟；`lateNotice` 迟到要留痕而不是假装准时 | `IntentParserTest.*`、`FlowTest.迟到太久的结果不补发` |
| `src/life/lifeStore.ts` | `companion/FollowUpStore.kt` | `durableWrite`：tmp + fsync + rename；读失败返回 null 且**不当作没有状态** | `FlowTest.G 状态可序列化往返` |
| `src/life/types.ts` | `companion/FollowUp.kt`、`CompanionConfig.kt` | Alarm 的 `source: 'plan' \| 'fallback'` 区分"她定的"与"兜底"；本项目用 `FollowUpSource` 三态扩展 | `FollowUpEngineTest.*` |
| `src/life/notify.ts` | `notify/Notifier.companionSays` | 通知只是通道之一；发布失败要能被调用方知道（返回布尔） | `FlowTest` 语境测试 + 真机 A-J |
| `src/life/earlyStop.ts` | 未移植 | 该机制针对"她自己的回合提前结束要不要追问"，一期没有多轮自主回合，故不引入 | — |
| `src/life/journal.ts`、`activity.ts` | 未移植（有对应能力） | 上游记录"她这段时间做了什么"；本项目用 `DialogueLog` 有界对话 + `DeliveryTrace` 替代 | `FlowTest` |

**明确未照搬**：Node 的 `setTimeout`、`Atomics.wait` 同步等待、DSH 的 `agent.steer` 会话接口、
`ctx.sessions.flush`。Android 侧用 `AlarmManager`、前台服务 tick、`CompletableJob`（协程）替代。

## SurfCore @ `d7f6b33d57b1667508c48206cbcc455815d6d336`

| 上游文件 | 本地实现 | 保留的行为 | 验证用例 |
|---|---|---|---|
| `src/character-persona.js` | `companion/Persona.kt` | 人格卡（稳定，每条注入）与运行状态（易变）分文件；`epoch` 让旧任务失效（本项目用 `CompanionState.epoch` + 事项 `version` + `personaVersion` 三层） | `FlowTest.C`、`FlowTest.E` |
| `src/purpose-engine.js` | `companion/SchedulePolicy.kt`、`FollowUpEngine.kt` | `evaluatePurposeTick` 的判定顺序（disabled→quiet→dailyMax→waiting→busy→idle）；`planNextFire` 的"停机只补一枪"；`inQuietHours` 跨零点；`pendingTtlDays` 过期清理；`textSimilarity` 去重的等价做法（本项目用 `dedupeKey` 精确去重，更保守） | `SchedulePolicyTest` 17 项、`FollowUpEngineTest` 17 项 |
| `src/memory-card.js` | `companion/FactEngine.kt` | `scoreMemory` 的加权项（主人 32 / 情绪 ≤24 / 约定 16 / 矛盾 14 / 太短 -12）；`addMemoryRecord` 的 2 天去重窗口；`touchMemories` 的复习效应；上下文只取有效条目 | `FactEngineTest` 16 项 |
| （本项目新增） | `companion/DeliveryGate.kt`、`DeliveryLog.kt` | 上游没有"生成前后双校验"这一步（它靠单轮 agent 回合），Android 侧生成是异步的，必须显式补上 | `FlowTest.C`、`FlowTest.迟到太久…` |

**明确未照搬**：B站素材、QQ inbox、`drives` 驱动向量、`goals` 目标假设、10GB 全量记忆卡、
`renderMemoryCard` 的 markdown 渲染、脱敏正则集合。原因：执行指令 §0 第 8 条禁止一期引入
联网采集、向量库与多角色；这些能力依赖"角色在别的平台有活动"，本项目没有。

## 许可

- DetoxDroid：GPL-3.0（阶段 1 已记录，仍未复制代码）。
- dsh-world、SurfCore：MIT（已读自有核心）。本项目**重写**而非复制，故无传染问题；
  若后续确实逐行复制，须按其条款保留声明。


---

# 阶段 2 补充：SurfCore 第二批借鉴（2026-10-29）

重新通读了 `Mieksido/surfcore` @ `d7f6b33d57b1667508c48206cbcc455815d6d336`（471 个文件），
只挑**能补上我们真实缺口**的两块移植，其余明确不碰。

## 借鉴了：对话收尾判断

| 上游 | 本地 | 保留的行为 |
|---|---|---|
| `src/conversation-closure.js` `assessConversationClosure` | `companion/ClosureEngine.kt` | 五类消息分类（道别/提问/请求/附和/有内容）；四项信号（我的继续意愿、对方的继续意愿、话题闭环、节奏疲劳）加权；**未答提问时硬阻止收尾** |
| 同文件 `closeGate` | `ClosureEngine.assess` 的 `awaitingTheirAnswer` | 把「不想回」和「逃避」分开：对方在问就必须先答 |
| 同文件 `makeClosureRecord` / `closureSuppressesWake` | `companion/ClosureRecord.kt` | 收尾留痕（summary + nextOpening）；软冷却只压**主动开口**，不压用户主动找他 |
| 同文件 `closurePromptBlock` | `ClosureEngine.promptBlock` | 只给「判断 + 依据」，不替模型决定；不把内部枚举名写进提示词 |

**解决的我们自己的问题**：之前 `userSays` 是无条件生成回复——用户说「嗯」「晚安」也照样回一句，
长期会变成"每句必回"的机械感。现在有了判断建议，并且收尾会落一条记录，
下次开口时能看到「上次收在这」和「下次可以这样接」，不再是从零开场。

## 借鉴了：出站内容审查

| 上游 | 本地 | 保留的行为 |
|---|---|---|
| `src/sensitive.js` `SENSITIVE_RE` | `companion/OutboundGuard.kt` | 拦本机路径与凭据赋值；**关键词必须带赋值形态**才判，避免误伤正常对话 |

**我们按 Android 场景做了两处收紧**（上游注释里也提醒过同类坑）：
1. 上游把「是/为」也算强赋值；我们拆成强弱两档——强形态（`=` / `:`）后面放宽，
   弱形态（「是/为」）后面必须是像令牌的拉丁串。原因是实测「我不记得密码是什么了」
   被打成了「[凭据已隐藏]」，正常句子被弄坏。
2. 已知密钥用**精确匹配**（Key 本来就在我们手里），不往正则里加 32 位十六进制——
   上游明确写过这会把正常表情 md5 / 图片名整条拦掉。

**解决的我们自己的问题**：上下文里有 API Key（存 shared_prefs），模型被追问完全可能把它、
把 `C:\Users\...` 这类本机路径吐出来。之前**没有任何出站检查**。

## 明确不借鉴（附理由）

| 上游能力 | 为什么不要 |
|---|---|
| QQ / B站 / 贴吧平台适配（`src/bridge.js` 850KB、`tools/commands/*`） | 执行指令一期禁止联网采集、B站、QQ |
| 语音包与安装器（`SUBSYSTEM-VOICE`、`dist/installer`） | 我们只用系统 TTS；引入会带来 FFmpeg/模型文件依赖 |
| 角色切换与快照（`character-persona.js` 的 snapshot/restore/archive） | 一期只有一个角色，多角色是阶段 3 之后的事 |
| `drives` 驱动向量、`goals` 目标假设、`observations` 账本 | 这些依赖"角色在别的平台有活动"才有素材；我们没有，硬引入会变成空转 |
| 10GB append-only 全量记忆卡 | 我们的事实量级是几百条，SQLite/JSON 足够；引入 JSONL 扫描是过度设计 |
| `history-catchup` 上线补历史 | 那是为 IM 掉线设计的。Android 侧没有"离线期间的消息"，无对应场景 |
| `greeting-schedule` 早安定时 | 它的核心价值是"补发窗口 + 跨模式发送方式"，我们的 `WakePlanner` 已覆盖迟到处理 |
| `role-card` 的模式标记解析 | 我们只有一个注入模式，没有一代/二代仿真之分 |
