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

| U-26 | 用户结束记 USER_ENDED；Gemini 跑在 Google App 里也算目标（核验与收起拦截层）；目标别名判定；旧会话文件缺新字段可读；新字段往返；沉浸舱别名与厂商拨号盘 | PASS（7） |

合计：**59 passed / 0 failed**（2026-10-29 云端：纯 JVM 构建 + Robolectric android-all 15 编译检查，未经 AGP；AGP 构建与 lint 需在本机重跑）。

## 代码审查疑点 R-1～R-7（2026-10-29，已改代码，待真机确认）

| 编号 | 疑点 | 处置 | 真机 |
|---|---|---|---|
| R-1 | Gemini 聊天界面常在 `googlequicksearchbox` 里，前台核验失败或 Gemini 自己被拦 | 会话加 `targetAliases`，沉浸舱把其它 Gemini 宿主包记为目标 | NOT_RUN |
| R-2 | 拦截层是可获焦全屏窗口，`rootInActiveWindow` 读到的是本 APP，「回到 Gemini」后拦截层收不起 | 前台改取最上层 `TYPE_APPLICATION` 窗口，再退回事件包名（排除自身）与 `rootInActiveWindow` | NOT_RUN |
| R-3 | 启动后固定 1.2 秒核验，Gemini 冷启动慢时误判失败 | 6 秒窗口内每 400ms 轮询，期满仍不是目标才失败 | NOT_RUN |
| R-4 | `EndActionReceiver` 未注册，通知栏「紧急结束」无效 | Manifest 注册（exported=false） | NOT_RUN |
| R-5 | vivo/iQOO 拨号盘可能是 `com.android.contacts`，会被拦 | 加入通话放行名单；实际包名待真机确认 | NOT_RUN |
| R-6 | 紧急结束后记成 COMPLETED，违反 D-04 | 会话加 `endedByUser`，核验通过后记 USER_ENDED | NOT_RUN |
| R-7 | 进程恢复后 0.8 秒核验，无障碍服务未连上即判失败 | 恢复后 15 秒窗口内轮询 | NOT_RUN |

## 阶段门槛判断

- 交付三项（规则、来源映射、目标设备验证方案）已齐：`DECISIONS.md`、`SOURCES.md`、本文档。
- 阶段 1 放行条件**未达成**：A-01～A-12 全部 NOT_RUN。
  因此不进入阶段 2，不对外宣称"限制已生效"。
