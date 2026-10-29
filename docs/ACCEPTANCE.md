# ACCEPTANCE

约定：
- **JVM 自动测试**与**真机**分开记，不互相替代。
- 真机用例未执行前一律写 `NOT_RUN`，保留在表里，不用总结性措辞掩盖。

## 设备条件

| 项 | 值 |
|---|---|
| 目标机型 | 待确认（旧记录为 iQOO 12 / Android 16） |
| 当前 adb | `adb devices` 为空，**无设备在线** |
| 本机构建 | 已验证（见 STATE） |

## 阶段 1 真机用例

| 编号 | 场景 | 做法 | 预期 | 实际 |
|---|---|---|---|---|
| A-01 | 权限前置 | 新装后直接点"进入沉浸舱" | 提示缺权限，不开始，不出现"进行中" | NOT_RUN |
| A-02 | 正常启动 | 授予两项权限后点"进入沉浸舱" | 切到 Gemini，状态 ENFORCING，常驻通知出现 | NOT_RUN |
| A-03 | 拦截抖音 | ENFORCING 时回桌面再打开抖音 | 全屏拦截层立即出现，显示"回到 Gemini" | NOT_RUN |
| A-04 | 吞返回键 | 拦截层上按返回 | 覆盖层不消失 | NOT_RUN |
| A-05 | 回到目标即收起 | 拦截层点"回到 Gemini" | 回到 Gemini，覆盖层自动消失，状态回 ENFORCING | NOT_RUN |
| A-06 | 微信放行 | 拦截期间打开微信 | 不拦，状态 AWAY_ALLOWED，通知仍在 | NOT_RUN |
| A-07 | 通话放行 | 拦截期间来电并接听 | 不拦；挂断后回目标 | NOT_RUN |
| A-08 | 到期自动恢复 | 等满 10 分钟 | 覆盖层消失、通知变"专注已结束"、可自由打开任何应用 | NOT_RUN |
| A-09 | 息屏后到期 | 进行中直接息屏，超过到期再亮屏 | 到期已解除，不再弹拦截层 | NOT_RUN |
| A-10 | 撤回权限 | 进行中关掉无障碍 | 状态 FAILED，通知"专注没能按预期执行"，不谎称在守护 | NOT_RUN |
| A-11 | 强行停止后重开 | 进行中在设置里强行停止本 APP，再打开 | 打开时按磁盘恢复判定；过期则立即解除。**不承诺**后台自动恢复 | NOT_RUN |
| A-12 | 目标不可启动 | 目标包改成未安装的包再开始 | 明确失败 TARGET_LAUNCH_FAILED，不显示为已开始 | NOT_RUN |

## 阶段 1 自动测试（已执行，52 项）

| 编号 | 覆盖 | 结果 |
|---|---|---|
| U-01 | begin → 核验 → ENFORCING | PASS |
| U-02 | 启动后前台不是目标 → FAILED | PASS |
| U-03 | 前台未知 → FAILED，不默认成功 | PASS |
| U-04 | 非白名单 → BLOCKING + ShowOverlay + 自有记录 | PASS |
| U-05 | 微信 → AWAY_ALLOWED | PASS |
| U-06 | 通话应用 → AWAY_ALLOWED | PASS |
| U-07 | 系统界面/输入法/自身 → 不改状态、无效果 | PASS |
| U-08 | 回到目标 → HideOverlay + ENFORCING | PASS |
| U-09 | 到期 → RELEASING + ReleaseRestrictions，不再新增拦截 | PASS |
| U-10 | 解除核验通过 → COMPLETED | PASS |
| U-11 | 解除核验失败 → FAILED(RELEASE_UNVERIFIED) | PASS |
| U-12 | 终态抗迟到前台回调 | PASS |
| U-13 | 终态抗迟到生效回调 | PASS |
| U-14 | 重复开始终态会话被拒 | PASS |
| U-15 | 重复拦截只保留一条自有记录 | PASS |
| U-16 | 无包名不拦截不报错 | PASS |
| U-17 | 非正时长被拒 | PASS |
| U-18 | 解除中不接受新前台事件 | PASS |
| U-19 | 存储：往返 / 无文件 / 损坏留痕 / 单份 / clear | PASS（5） |
| U-20 | 序列化往返与枚举稳定字符串 | PASS（3） |
| U-21 | 未知字段兼容、状态集合不重叠、规则版本可失效 | PASS（3） |
| U-22 | 恢复决策：无会话 / 读坏告知 / 终态清理 / 过期解除 / 恰好到期 / 退回 PREPARING / 失败与用户结束不复活 | PASS（8） |
| U-23 | 改系统时间不改截止时刻 | PASS |
| U-24 | 重启后按墙钟判定 | PASS |
| U-25 | 四类前台判定与输入法中性、未知不默认放行 | PASS（7） |

合计：**52 passed / 0 failed**。

## 阶段门槛判断

- 交付三项（规则、来源映射、目标设备验证方案）已齐：`DECISIONS.md`、`SOURCES.md`、本文档。
- 阶段 1 放行条件**未达成**：A-01～A-12 全部 NOT_RUN。
  因此不进入阶段 2，不对外宣称"限制已生效"。


---

# 阶段 2 验收

## 前置条件核对（本轮开始时的检查）

| 项 | 结果 |
|---|---|
| 阶段 1 锁定与恢复已在目标手机验收 | **否**。A-01～A-12 全部 NOT_RUN，无设备在线 |
| 因此本轮是否应扩展主动功能 | 按执行指令 §一.4 应当"先处理阻塞"；实际用户明确指示执行阶段 2，故本轮按指示推进，但**不宣称阶段 1 通过**，且主动功能设计成与限制功能零依赖（见下） |
| 限制与恢复是否依赖模型/网络 | 否。`GuardRuntime` 不 import 任何 companion 类；companion 通过 `focusActive` 单向读取专注状态 |

## 真机用例 A–J（全部 NOT_RUN）

| 编号 | 场景 | 最少测试步骤 | 预期 | 实际 |
|---|---|---|---|---|
| A | 停止输入后按约定主动联系 | 伙伴页说「十分钟后再聊刚才那个」→ 锁屏等待 | 约 10 分钟后收到通知/听到 TTS，内容是同一话题 | NOT_RUN |
| B | 「十分钟后再聊」旧提醒失效、新提醒接续 | 说一次约定 → 等 1 分钟 → 再说「二十分钟后再聊」→ 看「排着的事」页签 | 旧条目显示"已取消"，新条目接续同一 topic，到旧时间点不弹 | NOT_RUN |
| C | 生成期间取消，旧内容不播报 | 把模型换成慢速/断网 → 到点时立刻点"取消" | 不显示不播报那句话；事项状态不是"已说过" | NOT_RUN |
| D | 回应后继续交流，不重新提醒 | 收到主动消息后直接回复 | 继续对话；「排着的事」里该条变"已经说过" | NOT_RUN |
| E | 更正事实后不再引用旧事实 | 记忆页对一条点"更正"改掉 → 再聊相关话题 | 新回答用新信息；旧条目移到"已退出上下文" | NOT_RUN |
| F | 不回应时不重复轰炸 | 到点收到一条后完全不回 | 按 15 分钟退避重试，最多 3 次；之后当天不再重复 | NOT_RUN |
| G | 进程重建后事项仍在 | 设一条 10 分钟约定 → 划掉最近任务/杀进程 → 重开 | 「排着的事」里那条还在，到期仍会开口 | NOT_RUN |
| H | 后台/息屏/省电下的实际表现 | 三种条件各跑一次，记录实际迟到分钟数 | 允许迟到，但要在 UI 上如实显示；不谎称准点 | NOT_RUN |
| I | 网络/模型/TTS 分别故障，阶段 1 仍正常 | 断网 / 填错 Key / 静音设备，各跑一次专注会话 | 专注限制与到期恢复全部正常；伙伴侧只显示失败原因 | NOT_RUN |
| J | 非显式闹钟触发的主动开场 | 设置里打开"允许主动闲聊"→ 等若干小时 | 至少有 1 次开场带真实旧话题依据；允许选择不说话 | NOT_RUN |

## JVM 自动测试（已执行）

| 编号 | 覆盖 | 结果 |
|---|---|---|
| V-01 | 三种事实来源可区分；模型推测被压权 | PASS |
| V-02 | 去重窗口（同来源同文本 2 天内）与复习计数 | PASS |
| V-03 | 更正后旧事实退出上下文、新事实进入、supersededBy 正确 | PASS |
| V-04 | 忘记后不进入上下文、墓碑仍在、不被重新激活 | PASS |
| V-05 | 召回排序与条数上限 | PASS |
| V-06 | 约定创建、同话题替换（旧转 CANCELLED、版本 +1） | PASS |
| V-07 | 依据为空拒绝创建 | PASS |
| V-08 | 改期只动时间、版本 +1 | PASS |
| V-09 | 取消后不可投递 | PASS |
| V-10 | 投递串行：DELIVERING 不可再次取走 | PASS |
| V-11 | 版本不符（改期/取消）导致结果丢弃 | PASS |
| V-12 | 正常投递记为完成并累加今日次数 | PASS |
| V-13 | 失败按退避重排、不增加今日次数 | PASS |
| V-14 | 超过重试上限转 FAILED | PASS |
| V-15 | TTL 过期清理 | PASS |
| V-16 | dueNow 取最早到期项 | PASS |
| V-17 | 跨零点免打扰、起止相同不启用、非跨零点区间 | PASS |
| V-18 | 判定顺序：disabled / quiet / daily-max / waiting / no-topic / user-active / run | PASS |
| V-19 | 专注中禁言（优先级高于到期），专注结束恢复 | PASS |
| V-19b | 专注中禁言的两条专门用例（含恢复） | PASS |
| V-20 | 首次排程、停机只补一枪、落在免打扰被推后、达上限推次日 | PASS |
| V-21 | 十分钟/半小时/两小时/等下再聊、中文数字 1..99 | PASS |
| V-22 | 取消解析、改锁定被识别为拒绝而不执行、普通聊天无意图 | PASS |
| V-23 | 流程 A/B/C/D/E/F/G/I/J 的逻辑等价验证（`FlowTest`） | PASS |
| V-24 | 迟到超过上限丢弃、窗口内允许 | PASS |
| V-25 | 上下文包含人格与"这次为什么要开口"，且不暴露内部字段名 | PASS |

合计：**124 passed / 0 failed**（阶段 1 的 52 项 + 阶段 2 的 72 项）。

## 阶段 2 放行条件判断

- 执行指令 §7 的放行条件：「主动、记忆连续、回应处理均有证据；只轮播台词或只演示前台聊天不算通过」。
- 现状：**逻辑层证据齐备，设备层证据为零**。因此阶段 2 **不判通过**，标记为"待真机验收"。
- 已知缺口（不掩盖，详见 STATE）：专注中禁言已补；更正/忘记已有 UI 入口；
  主动闲聊默认关闭；墓碑非物理删除。


---

# 云端复测整改（2026-10-29）

依据《focusmate-云端复测报告》，核对提交 `198885d`。五条问题全部确认成立并已修复。

## 逐项处理

| 编号 | 问题 | 修复 | 反向验证（证明用例能抓住） |
|---|---|---|---|
| F-01 | APK 缺 `INTERNET` 权限 | Manifest 补声明 | 临时删掉后 `aapt2 dump permissions` 报 `INTERNET in APK: False`；恢复后为 `True` |
| F-02 | 遗忘/更正后原句仍从近期对话进入模型输入 | 事实与对话按来源关联：`Utterance.hidden` 墓碑 + `Fact.sourceUtteranceId` + `forgetWithSource/correctWithSource`；`CompanionContext` 组装时过滤 hidden | 临时去掉对话隐藏后 `forgottenFactMustNotLeakThroughDialogue` 失败 |
| F-03 | 无关回复把所有未来约定标成完成 | 新增 `AWAITING_REPLY` 状态；`closeOnUserReply` 只结算该状态 | 临时改回 `it.open` 后 `unrelatedReplyMustNotCloseFutureAgreement`、`replyClosesOnlyAwaitingItem` 双双失败 |
| F-04 | 迟到的失败回调复活已取消事项 | `markFailed`/`releaseDelivery` 增加 `issuedVersion` 与「必须仍在 DELIVERING」守卫 | 临时去掉守卫后 `lateNetworkFailureMustNotResurrectCancelledItem`、`lateFailureMustNotOverwriteRescheduledAgreement` 失败 |
| F-05 | 生成期间记忆变化，旧结果仍可通过闸门 | 引入 `CompanionState.memoryVersion`（只在记忆/配置变化时 +1）；`DeliveryGate.check` 增加记忆版本与「此刻是否仍允许开口」两项 | 临时删掉记忆版本检查后 `factChangeMustInvalidateInFlightProactiveOutput` 失败 |

## 另外两项源码缺口

| 项 | 处理 |
|---|---|
| `SettingsTab` 没有闲聊开关 | 已加开关控件 + `setAllowSmallTalk`/`updateSchedule` API；`AWAITING_REPLY` 条目也可取消/改期 |
| 主动伙伴没有自己的后台/开机唤醒链路 | 新增 `CompanionScheduler`（独立 requestCode 20，与专注到期闹钟互不复用）+ `CompanionWakeReceiver`；`BootReceiver` 在恢复专注后一并恢复伙伴 |

## 本轮自动测试

| 编号 | 覆盖 | 结果 |
|---|---|---|
| C-01 | 遗忘后原句不进模型输入 | PASS |
| C-02 | 更正后旧句不进输入、新内容进输入 | PASS |
| C-03 | 无关回复不关闭未来约定 | PASS |
| C-04 | 回应只结算 AWAITING_REPLY | PASS |
| C-05 | 取消后迟到失败不复活 | PASS |
| C-06 | 改期后迟到失败不覆盖新时间 | PASS |
| C-07 | 记忆变化使在途主动结果失效 | PASS |
| C-08 | 生成结束已进入专注时丢弃 | PASS |
| C-09 | 状态未变时仍正常发送（防止闸门过严） | PASS |

合计：**133 passed / 0 failed**（原 124 + 新增 9）。

## 仍未获得证据的部分（不得改为 PASS）

- 设备层：UI 点击、覆盖层、到期恢复、TTS 实际发声、通知实际弹出、后台/息屏/省电行为 —— 全部 NOT_RUN。
- 真实模型调用：本机没有配置 Key。F-01 只证明「APK 具备该权限」，**不证明**请求真的成功。
  报告要求的两条路径（有效 Key 成功 / 无效 Key 失败）仍需在设备上分开验证。
- 云端模拟器无 `/dev/kvm` 未能上线，本轮同样没有模拟器证据。

## 用户的手机优先测试顺序（沿用报告建议）

1. 缺权限不能假装开始；授权后切娱乐应用应出现拦截。
2. 拦截层「回到目标」是否收起；主动结束与自然到期分别测。
3. 配好模型发一句普通消息，确认真实回复；失败时保留错误提示。
4. 约定稍后联系 → 再聊一句无关的话 → 检查未来事项是否还在（F-03）。
5. 记住一条信息 → 更正或忘记 → 继续问相关话题，检查旧信息是否被重新引用（F-02）。
6. 慢请求期间取消提醒 → 等成功/失败返回 → 确认都不再发出且取消状态不变（F-04）。
7. 最后测熄屏、省电、进程重建、语音发声与长时间运行。


---

# 二次复核整改（2026-10-29）

依据《focusmate-整改二次复核》，核对提交 `078c27c`。四条问题全部确认成立并已修复。

## 逐项处理与反向验证

| 编号 | 问题 | 修复 | 反向验证（改回旧行为必须失败） |
|---|---|---|---|
| R-01 | 旧数据只有 `sourceRef`，升级后 `sourceUtteranceId` 为 null，遗忘挡不住原句 | 新增 `FactMigration`：只从能明确解析的 `utterance:<id>` 恢复关联，幂等、不删数据；`load()` 时执行 | 让迁移直接返回 null → `legacySourceRefMustStillSupportForget`、`migrationIsIdempotentAndDoesNotDeleteData` 失败 |
| R-02 | 遗忘前重复说同一句，只有第一次被隐藏 | `Fact.sourceUtteranceIds` 多来源；`add()` 去重分支合并来源；遗忘/更正隐藏**全部**来源 | 去重分支不合并 → `repeatedStatementBeforeForgetMustAlsoBeHidden` 失败 |
| R-03 | 更正事实后，关联事项仍带旧依据进提示词 | `correctWithSource` 同步作废以旧句为依据的事项；`CompanionContext` 增加第二道保险：依据句子 hidden 就不渲染 | 不作废事项 → `correctedFactMustNotLeakViaFollowUpEvidence` 失败 |
| R-04 | 免打扰/专注/上限时仍把过去的时刻排给 AlarmManager | 新增纯函数 `WakePlanner.plan`；`CompanionScheduler.scheduleAt` 只接受策略结果；`nextWakeAt` 不再掺全局 `nextDueAtMillis` | 去掉策略判断 → `quietHoursMustNotRearmAlarmInPast`、`focusAndDailyMaxDeferInsteadOfFiringNow`、`dueItemSchedulesNowNotPast` 三项失败 |

## 本轮自动测试

新增 `RepairBoundaryTest`（11 项），覆盖：旧来源迁移、迁移幂等、重复陈述全隐藏、
遗忘后重新提供仍正常、更正级联、hidden 依据不渲染、免打扰不排过去、关闭/空队列取消闹钟、
专注与次数上限推迟、到期即 now 不给过去、等回应不唤醒。

**合计 144 passed / 0 failed**（原 133 + 新增 11）。
lint 0 errors / 10 warnings。APK 23,945,338 bytes，含 INTERNET（aapt2 实测）。

## 仍未获得证据（不得改为 PASS）

- 真实模型成功/失败两条路径、UI 点击、通知弹出、TTS 发声、真实后台唤醒频率与耗电：**NOT_RUN**。
- R-04 只证明了"算出来的时间不再落在过去"，没有在设备上测量 WakeLock/Alarm 的实际触发频率。
- 本轮曾在本机模拟器上拿到过一次拦截层生效与倒计时显示的截图证据，
  但那是另一套环境（本机 emulator），与本报告的云端环境不同，不合并计算。
