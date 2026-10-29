# 阶段 3 交接：现状盘点

日期：2026-10-29。核对提交 `4a48c91`（本轮改动在 `4a48c91` 之后，见本轮提交）。

按指令要求，先列「已有可复用 / 尚缺 / 阻断」，再执行。

## 一、已有可复用（不重写）

| 能力 | 位置 | 运行证据 |
|---|---|---|
| 专注会话状态机 | `core/FocusGuard.kt` | JVM 18 项 + 模拟器 A-01～A-11 |
| 覆盖层拦截 | `overlay/BlockOverlay.kt` | 模拟器实测：YouTube 被拦、返回键无法退出 |
| 无障碍前台识别 | `service/GuardAccessibilityService.kt` | 模拟器实测：切应用状态正确迁移 |
| 到期本地恢复 | `runtime/GuardRuntime.kt` + `alarm/` | 模拟器实测：到期 `completed`，YouTube 可开 |
| 进程重建恢复 | `core/Recovery.kt` | 模拟器实测：`expiresAtMillis` 前后一致 |
| 人格（临时） | `companion/Persona.kt` | 决定 Q-06 未定，先用临时人格 |
| 事实记忆 + 更正/遗忘 | `companion/FactEngine.kt` | JVM 16 项（含来源多关联、迁移） |
| 待跟进事项 + 改期/取消 | `companion/FollowUpEngine.kt` | JVM 17 项 |
| 调度（禁言/免打扰/上限） | `companion/SchedulePolicy.kt` + `WakePlanner.kt` | JVM 17 + 11 项 |
| 对话收尾判断 | `companion/ClosureEngine.kt` | JVM 20 项 |
| 出站内容审查 | `companion/OutboundGuard.kt` | JVM 7 项 |
| 模型接口 | `companion/OpenAiCompatibleClient.kt` | 未接真实 Key |

## 二、本轮（阶段 3）补齐的缺口

| 缺口 | 补法 |
|---|---|
| 模型设置没有连接测试 | `ModelSettings.testConnection` + 设置页「测试连接」按钮，**已实测**返回明确失败原因 |
| 聊天没有「发送中/失败」状态 | `ChatSendState`（Sending/Sent/Failed）+ UI 状态条；失败可重试、发送中禁用输入 |
| TTS 无法关闭 | `CompanionConfig.ttsEnabled` + 设置页开关；关闭后完全不发声 |
| 界面暴露内部枚举 | `CompanionStatus`：状态说明、专注标签、失败原因全部翻成人话；首页不再显示 `A11Y_UNAVAILABLE` 与版本号 |

## 三、仍缺 / 阻断（如实列）

| 项 | 状态 | 影响 |
|---|---|---|
| 真实模型调用 | **NOT_RUN** | 没有可用 Key；Q-06 服务商未定。用测试服务也只能证明流程通，不能算真实模型通过 |
| 手机上的一切 | **NOT_RUN** | 只有模拟器（Android 14 / emulator-5554）。厂商省电、真机通知、TTS 发声、通话放行均未验 |
| A-07 来电放行 | NOT_RUN | 模拟器无电话，需真机 |
| A-09 息屏后到期 | NOT_RUN | 上一轮测试中断，未补 |
| A-12 目标未安装 | NOT_RUN | 未构造 |
| 主动交流端到端 | **NOT_RUN** | 需要模型；无 Key 时 `deliver` 会走 Unconfigured 分支 |

## 四、本轮实测记录（模拟器 emulator-5554 / Android 14）

| 编号 | 场景 | 结果 |
|---|---|---|
| A-01 | 缺权限拒绝开始 | PASS — 提示「缺少显示在其他应用上层权限」，未开始 |
| A-02 | 启动→倒计时→通知 | PASS — 04:44 倒数，notification id=1001 channel=focus_guard |
| A-03 | 受限应用被拦 | PASS — state=blocking，overlayFor=[youtube] |
| A-04 | 按返回键不消失 | PASS — mCurrentFocus=focusmate-block（修复前 FAIL，一按就绕过） |
| A-05 | 回到目标即收起 | PASS — state=enforcing，焦点回 Google，覆盖层移除 |
| A-06 | 允许应用放行 | PASS — state=away_allowed，拨号器未被拦 |
| A-08 | 到期自动恢复 | PASS — state=completed，failure=null，之后 YouTube 可正常打开 |
| A-10 | 撤回无障碍权限 | PASS — state=failed，原因「超过 5 秒仍未恢复」 |
| A-11 | 杀进程重建 | PASS — expiresAtMillis 前后完全一致 |
| S-01 | 模型连接测试按钮 | PASS（失败路径）— 「连接失败：还没填完整」 |
| S-02 | 主动交流状态说明 | PASS — 「还没有待跟进的约定。跟我说…就能建一个」 |
| S-03 | TTS 开关存在且可切 | PASS（界面）— 「朗读收到的消息 / 关掉」 |

## 五、限制能力与可绕过边界（不许美化）

**能拦住**：正常点图标、从最近任务切回、从通知打开、按返回键（已实测）。

**拦不住**：
- 用户去系统设置关掉无障碍或悬浮窗权限（实测：会如实判 FAILED，但限制确实失效）；
- 用户在设置里「强行停止」本 APP；
- 用户卸载本 APP。

**未承诺**：锁屏状态下继续拦截（一期不做锁屏 App 级锁定）。
这不是「整台手机无法绕过的锁机」，是**指定应用的覆盖层拦截**。
