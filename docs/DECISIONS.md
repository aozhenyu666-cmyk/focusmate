# DECISIONS

每个决定有编号。用户明确说过的记原话或直接选择；技术细节由实现方决定并注明理由。
未确认项单列在最后一节，不得当作已同意。

---

## D-01 项目独立，旧项目只读

- 来源：执行指令 §0 第 1 条。
- 规则：新项目目录 `C:\focusmate`（工作区内通过 junction 可见为 `default-workspace\focusmate`），
  应用 ID `com.focusmate`，与旧「同桌」「莫宁」无共用代码、无共用签名。
- 现状：旧项目 C3 专注守护源码未落盘，只有文字记录 `_pet-reports/status-20261025/C3_FOCUS_GUARD_STATE.md`；
  其中记录的 F-01～F-08 八个已复现缺陷全部作为本次设计约束（见 D-09）。

## D-02 一期范围：只有"限制真实生效 + 到点真恢复"

- 来源：执行指令 §0 第 3、8 条。
- 规则：一期不含屏幕陪看、自动上网、B站/QQ、跨设备、多 Agent、向量库、复杂动画。
- 一期不含锁屏、挂起应用（SUSPEND）、停用应用（DEACTIVATE）、Force Stop、设备所有者。
  这些都要额外系统权限或重置设备，未取得授权前一律不做。

## D-03 「十分钟后再聊」只改对话跟进，不改锁定

- 来源：执行指令 §1.2 提供的默认规则。
- 规则：该句只修改跟进事项的下次时间，不改变专注会话的 `expiresAtMillis`。
  改变锁定时长必须走单独的确认流程（阶段 3 实现）。
- 实现：当前版本尚无对话与主动跟进（阶段 2），此规则先入档，阶段 2 必须遵守。

## D-04 提前结束：一期只提供"立即结束 + 留痕"

- 用户尚未在 A/B/C 三档中做出选择。
- 临时规则（覆盖层与通知栏两处出口都叫「紧急结束」）：
  立即解除，不等冷静期；但必须记入会话记录，且状态为 `USER_ENDED` 而不是 `COMPLETED`，
  以便复盘区分「到点结束」与「自己结束」。
- 待用户选择后按选择改写；若用户选「正常流程不能提前结束」，则移除 UI 出口，只保留故障恢复通道。

## D-05 主动交流：一期不实现

- 用户尚未确认主动交流的场景、频率、语音与否。
- 规则：本版本没有任何"角色主动开口"。UI 文案与通知也不暗示有人格存在。
- 依据：执行指令 §2 要求"不能只实现闹钟就宣称有活人感"，故宁可先不做。

## D-06 限制路径：无障碍前台识别 + 无障碍覆盖层（BLOCK 语义）

- 选择理由：不需要 root、不需要 Shizuku、不需要设备所有者、不清数据，是所有方案里唯一
  能在普通手机上立刻验证的强一档限制。
- 对照 DetoxDroid 三档：
  - BLOCK（覆盖层，要悬浮窗权限）→ 采用，但改用 `TYPE_ACCESSIBILITY_OVERLAY`，
    因为普通悬浮窗在部分厂商系统上会被"上层显示"策略削弱。
  - SUSPEND（要 Shizuku）→ 不采用，用户未授权装 Shizuku。
  - DEACTIVATE（要设备所有者）→ 不采用，需清设备数据。
- 真实强度声明（必须原样传达给用户）：
  - 能拦住：正常点图标、从最近任务切回、从通知打开。
  - 拦不住：用户去系统设置关掉无障碍或悬浮窗；用户在设置里强行停止本 APP；
    用户卸载本 APP。以上都属于"用户主动解除"，不做对抗。
  - 不承诺：锁屏状态下继续拦截（一期不做锁屏）。

## D-07 【用户新增】Gemini 沉浸舱：约十分钟内只允许 Gemini + 通话 + 微信

- 用户原话：「有一个功能…点开它之后，会弹出和 Gemini 对话的聊天，就是 APP，就切到 Gemini 的页面嘛。
  这个时候会维持大概 10 分钟左右，这个时候只能和 Gemini 对话。当然了，通话、微信这些还是能用的，
  但是别的 APP 都不行」。
- 落成规则：
  - 入口：首页按钮「进入沉浸舱 · 10 分钟」。
  - 动作：先落盘会话（`kind = GEMINI_DOCK`），再启动 Gemini，然后 1.2 秒后核验前台确实是 Gemini。
    核验不通过就是 `FAILED`（`TARGET_LAUNCH_FAILED` 或 `A11Y_UNAVAILABLE`），不显示为已开始。
  - 时长：默认 600000 ms（10 分钟），写在 `Presets.DOCK_DURATION_MILLIS`，UI 不硬编码。
  - 放行集合：Gemini 目标包 + 电话/Dialer/Telecom/InCallUI + `com.tencent.mm`（微信）
    + 系统界面/输入法/权限框/本 APP 自己（`AllowPolicy` 的 NEUTRAL）。
  - 其余应用：进入即显示全屏覆盖层，覆盖层吞掉返回键，提供「回到 Gemini」与「紧急结束」两个出口。
  - 到期：本地判定 `now >= expiresAtMillis`，直接解除，不等待模型或网络。
- 待确认的两个点（**必须由用户回答，不得自行假设**）：
  1. 「微信能用」是指微信整体可用（当前实现），还是只允许微信的音视频通话界面？
     若只要通话界面，就必须改成按 Activity/界面判定，强度与稳定性都会下降，且要重新验收。
  2. 10 分钟到期是直接放开全部（当前实现），还是先给一个确认步骤再放开？

## D-08 时间语义：墙钟存截止，单调钟只用同一次开机

- 规则：`expiresAtMillis` 是墙钟时刻，唯一持久化的时间；
  `SystemClock.elapsedRealtime()` 只在同一次开机内用于节流与 tick，**绝不存盘**。
- 用户改系统时间：`FakeClock.jumpWall()` 单测覆盖——改时间不会改写已存的截止时刻；
  但因为判定用墙钟，把系统时间往前调确实会让会话提前结束。这一点在验收里作为已知行为记录，
  不宣称"防改时间"。
- 重启：单调钟归零，会话按墙钟重新判定（`FakeClock.reboot()` 单测覆盖）。

## D-09 旧实现的八个缺陷在本项目里的对应处置

| 旧编号 | 旧问题 | 本项目处置 | 证据 |
|---|---|---|---|
| F-01 | ARMED 收到非目标前台落到 `else -> None`，永远不守护 | 状态机里 `AllowPolicy` 只有四类结果，无兜底分支；`BLOCK` 一定产生覆盖层 | `FocusGuardTest.进入非白名单应用产生拦截` |
| F-02 | 宽限到期只 show 覆盖层，不自动返回 | 覆盖层按钮触发 `returnToTarget()`，900ms 后核验，未回到则退避重试，上限 5 次 | `GuardRuntime.verifyReturnedToTarget` |
| F-03 | 用户自己回到目标时覆盖层不收起 | `onForeground(TARGET)` 在 `BLOCKING` 状态下产生 `HideOverlay` | `FocusGuardTest.回到目标时收起拦截层` |
| F-04 | 存盘失败仍报"已开始" | `applyEffects` 先 persist，失败则不执行任何系统动作并发失败通知 | `GuardRuntime.applyEffects` |
| F-05 | 用桌宠 Service 的 Context 加 `TYPE_ACCESSIBILITY_OVERLAY`，addView 必抛被吞 | 覆盖层只能由无障碍服务的 window context 创建；未就绪直接返回 false 并转 `FAILED` | `OverlayHost.show` / `BlockOverlay.show` |
| F-06 | `verifyReturn` 硬编码成功，旧会话回调串扰 | 所有延迟回调校验 `sessionId`，且 `SessionStates.isTerminal` 拒绝改写 | `FocusGuardTest.终态不被迟到的生效回调改写` |
| F-07 | 每次窗口事件刷新 updatedAt，宽限被无限顺延 | 本项目没有宽限期这个可被事件刷新的字段；到期只看 `expiresAtMillis` | `FocusGuardTest.到期走本地解除` |
| F-08 | SOFT 也走全屏拦截，与 STRICT 无差异 | 一期只有一档强度，不存在 SOFT/STRICT 两种名义；UI 不提供"软/硬"开关，避免名义差异 | — |

## D-10 构建环境（影响可复现性，必须记录）

- 项目真实路径 `C:\focusmate`，工作区内 junction 指过去。
  原因：AGP 在含非 ASCII 字符（`敖震宇`）的工程路径上直接拒绝，且 Gradle 8.11.1 的
  test worker 在非 ASCII 的 `GRADLE_USER_HOME` 下起不来（`ClassNotFoundException: GradleWorkerMain`）。
- `GRADLE_USER_HOME` 与 Gradle 发行版都复制到了 ASCII 路径（`C:\gh`、`C:\gradle\gradle-8.11.1`）。
- `local.properties` 写死 `sdk.dir=C\:\\Android\\Sdk`；环境变量 `ANDROID_HOME` 指向的
  `E:\ANDROID_SDK` 在本机不存在，不可依赖。
- compileSdk/targetSdk = 35（androidx.core 1.15 / activity 1.10 强制要求 ≥35），minSdk = 30。

---

## 尚未确认（阻塞对应阶段，不得当作已同意）

| 编号 | 问题 | 阻塞 |
|---|---|---|
| Q-01 | 锁机强度最终选 A/B/C 哪一档（D-06 现为 B 的实现） | 阶段 1 验收口径 |
| Q-02 | 提前结束是否要冷静期（D-04 现为"立即结束"） | 阶段 1 验收 |
| Q-03 | 微信放行粒度：整体 or 仅通话（D-07 第 1 点） | D-07 验收 |
| Q-04 | 到期是否直接放开（D-07 第 2 点） | D-07 验收 |
| Q-05 | 主动交流场景/频率/语音（D-05 现为不做） | 阶段 2 |
| Q-06 | 角色称呼、说话风格、模型服务商与模型名 | 阶段 2 |
| Q-07 | 目标手机型号与 Android 版本（旧记录为 iQOO 12 / Android 16，本机 adb 当前无设备） | 阶段 1 真机验收 |
