# STATE

## 最新（2026-10-29，分支 `claude/v0.2-agent`）

- 版本 **0.2.0-agent / versionCode 2**：在专注锁之上接入主动 AI 伙伴（D-11）。
- 构建：GitHub Actions（`.github/workflows/android.yml`）跑单测 + lint + assembleDebug，
  每个分支推送发布预览包：`https://github.com/aozhenyu666-cmyk/focusmate/releases/download/preview-claude-v0-2-agent/focusmate.apk`
- 自动测试：107 passed / 0 failed。真机：A-01～A-12、R-1～R-7、B-01～B-15 全部 NOT_RUN。
- 下一步：用户装预览包、填 Key，按测试单跑真机；按结果修。

---

（以下为阶段 1 记录）

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
| 远端仓库 | https://github.com/aozhenyu666-cmyk/focusmate （private） | `git push` 成功，`main` = 1d82a9d 之后 |

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

## 2026-10-29 代码审查修复（分支 `claude/phase1-device-fixes`）

- 修了 R-1～R-7 七个读代码发现的真机风险，见 `ACCEPTANCE.md`。
- 单测 **59 passed / 0 failed**（新增 7）；云端无法访问 dl.google.com，未跑 AGP 构建与 lint，需本机 `build.ps1` 重跑出 APK。
- 会话文件新增 `targetAliases`、`endedByUser` 两个带默认值字段，旧文件可读（单测覆盖）。
- 已知取舍：沉浸舱期间 Google App 整体算作目标（Gemini 运行在里面，无法按包名区分）。

## 下一个动作

1. 用户回答 Q-03（微信放行粒度）与 Q-04（到期是否直接放开），这两条决定 D-07 的验收口径。
2. 接上真机后按 `docs/ACCEPTANCE.md` A-01～A-12 逐条跑并回填。
3. Q-01/Q-02 有答复后调整 D-04/D-06 并重跑受影响用例。
