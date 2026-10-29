# 交接文档 · 高频随机主动交流引擎 V1

生成时间：2026-10-29 23:50（Asia/Shanghai）
分支：`feat/proactive-engine-v1`
提交：`a617c10`
PR：https://github.com/aozhenyu666-cmyk/focusmate/pull/1
基线：`main` @ `a0c5206`

---

## 0. 一句话结论

**源码已完成并曾编译通过，但当前这台机器卡在一个环境级构建故障上：测试没跑、APK 没出、设备验收全部 NOT_RUN。**

按交付要求，这个分支**不能合并、不能算完成**。接手的人第一件事是把构建修好。

---

## 1. 实际检查到的故障（含证据）

指令列出的 8 条风险，逐条核对源码后**全部确认存在**：

| # | 缺陷 | 证据位置 | 实测值 |
|---|---|---|---|
| 1 | 无约定就不闲聊 | `CompanionConfig.kt` | `val allowSmallTalk: Boolean = false` |
| 2 | 频率过低 | `CompanionConfig.kt` | `val dailyMax: Int = 3` |
| 3 | 间隔僵硬 | `CompanionConfig.kt` | `gapMinMillis = 90L` / `gapMaxMillis = 240L`（分钟） |
| 4 | 随机性不足 | `CompanionRuntime.kt` | `markSent(..., roll = 0.5, ...)` 写死 |
| 5 | 无待办即取消唤醒 | `WakePlanner.kt:37` | `?: return null`（无 PENDING 事项直接返回 null） |
| 6 | 无记忆即沉默 | `CompanionRuntime.kt` | `recall(state, "", limit = 3).firstOrNull() ?: return` |
| 7 | 通知依赖 UI | `CompanionScreen.kt` | `onUtterance` 全文仅 1 处赋值，在 Compose 的 LaunchedEffect 里 |
| 8 | 通知互相覆盖 | `Notifier.kt` | `manager.notify(ID_PARTNER, n)`，`ID_PARTNER = 1003` 固定 |

第 7 条是「为什么不主动」的**最直接原因**：后台生成了消息，但通知回调只有打开过伙伴页才绑定。用户不打开 APP → 生成成功但没人看得到。

---

## 2. 借鉴了哪些开源项目的哪些机制

| 项目 | 许可证 | 借鉴的机制 | 是否复制代码 |
|---|---|---|---|
| [微光主动关怀](https://github.com/yuntaojinghong/astrbot_plugin_proactive_care) | **AGPL-3.0** | ① 判定从便宜到昂贵（先过零成本闸门再调模型）② 触发原因写进提示词 ③ 按「会话+日期」作随机种子，当天排期重启不变 | **否** |
| [astrbot_plugin_proactive_chat](https://github.com/Pancakes-Labs/astrbot_plugin_proactive_chat) | **AGPL-3.0** | 触发类型分类（idle/followup/random/schedule/instant）的组织方式 | **否** |
| [大肥鱼](https://github.com/whyever77/dafeiyu-astrbot) | MIT | 仅阅读项目结构 | 否 |
| [SurfCore](https://github.com/Mieksido/surfcore) | MIT | 已在阶段 2 借鉴过（收尾判断、出站审查） | 前几轮已重写 |

**许可证说明**：两个主要参考项目是 AGPL-3.0。本项目**没有复制任何一行代码**，只借鉴设计思想并独立实现为 Kotlin。如果后续有人想直接搬它们的中文提示词或调度算法实现，必须按 AGPL 处理整个工程的许可与分发。

---

## 3. 实际修改、新增的源码文件

### 新增 `app/src/main/java/com/focusmate/proactive/`（13 个文件）

| 文件 | 职责 | 关键设计 |
|---|---|---|
| `ProactiveConfig.kt` | 配置 | 小时权重表（晚间 0.95 / 凌晨 0.01），让触发时间天然不均匀 |
| `ProactiveMode.kt` | 三档预设 | DAILY(6/时) / HIGH(12/时) / EXPERIMENT(30/时)。**改的不只是次数，命中率与最小间隔一起变** |
| `RandomSource.kt` | 随机源 | 生产用真随机；`SeededRandomSource` 供测试复现 |
| `RandomTriggerEngine.kt` | 摇骰核心 | 8 道闸门 + 概率调制 + 退避 + 长静默恢复 + 不等距机会 |
| `ContextTriggerEngine.kt` | 情境触发 | 6 类情境，**始终含 RANDOM_SMALL_TALK 兜底** |
| `InteractionPolicy.kt` | 互动仲裁 | 互动率统计、突发窗口滚动、随机与约定分账 |
| `ProactiveMessage.kt` | 消息模型 | 四态分离：GENERATED / NOTIFIED / SHOWN / READ |
| `ProactiveState.kt` | 持久化 | 原子写（tmp+fsync+rename），读坏留痕不静默 |
| `MessageDispatcher.kt` | **独立投递** | 落盘 → 通知 → 语音；每条独立通知 ID |
| `ProactiveEngine.kt` | 编排 | 闸门 → 概率 → 情境 → 生成 → 投递，串行无并发 |
| `ProactiveRuntime.kt` | 单例 | 保证只有一个引擎实例（两个会重复发消息） |
| `ProactiveService.kt` | 后台服务 | 独立前台服务，20 秒 tick，不依赖 UI |
| `CompanionProactivePrompt.kt` | 提示词 | 按情境生成不同提示词，附「别重复最近说过的」 |

### 修改既有文件（3 个）

| 文件 | 改动 | 增加行数 |
|---|---|---|
| `notify/Notifier.kt` | `companionSays(text, notificationId)` 重载 + `canNotify()` | +13 / -2 |
| `companion/CompanionRuntime.kt` | 暴露 5 个只读/受限接口给 proactive | +31 |
| `AndroidManifest.xml` | 注册 `ProactiveService`（specialUse） | +9 |

### 新增测试（1 个文件，16 项）

`app/src/test/java/com/focusmate/proactive/RandomTriggerEngineTest.kt`

覆盖指令强制验收项：种子分布差异、概率 0/1 边界、晚间 vs 凌晨、退避递减、长静默恢复、不等距间隔、高频模式快于日常模式、8 道闸门各自生效。

---

## 4. 随机触发参数和运行策略

### 默认参数（DAILY 模式）

| 参数 | 值 | 说明 |
|---|---|---|
| 每小时机会数 | 6 | 不是发言数，是摇骰次数 |
| 单次命中率 | 0.50 | 再乘小时权重与状态系数 |
| 最小间隔 | 6 分钟 | 两次真正发出之间 |
| 突发限流 | 8 条 / 小时 | 硬上限，冲不破 |
| 每日上限 | 80 | 实验模式设 0 = 不限 |
| 免打扰 | 23:00–08:00 | 跨零点正确处理 |
| 沉默退避 | [1.0, 0.7, 0.45, 0.25, 0.1] | 连续不回应逐级降 |
| 长静默恢复 | 沉默 ≥6 次后概率下限 0.08 | 防止永久沉默死锁 |
| 互动加成上限 | 1.35× | 用户常回就多说 |

### 小时的权重分布（决定「什么时候更容易开口」）

```
00-05: 0.01~0.03   （几乎不说）
06-08: 0.15 → 0.70  （起床爬升）
09-17: 0.45~0.60   （白天平稳）
18-21: 0.70 → 0.95  （晚间高峰）
22-23: 0.70 → 0.30  （收尾）
```

### 闸门顺序（从便宜到昂贵，命中前不调模型）

```
关闭 → 暂停 → 通话 → 专注中 → 免打扰 → 日上限
     → 突发限流 → 最小间隔 → 约定让路 → 算概率 → 摇骰
```

「约定让路」是刻意设计的：有重要约定快到期时，随机消息主动让步，避免随机闲聊挤掉真正该说的提醒。

### 后台策略

- `ProactiveService` 前台服务，20 秒 tick（判定是纯内存计算，不烧 token）
- 只有命中才调模型
- 服务独立于 UI，开机/进程重建后仍可被拉起

**诚实的边界**（不能承诺）：
- Android 精确闹钟有频率限制，做不到「每分钟强制唤醒」
- Doze / 厂商省电会推迟，可能迟到
- 强行停止后收不到任何广播，必须用户重新打开
- 没有用高频 WakeLock，也没有滥用无障碍去绕过系统限制

---

## 5. 单元测试与 Android 测试结果

| 项目 | 结果 |
|---|---|
| 编译 | **曾通过**（新增 13 个文件全部编译成功，见下方时间线） |
| `RandomTriggerEngineTest`（16 项） | **NOT_RUN** — 构建故障，未能执行 |
| 其余既有 176 项测试 | **NOT_RUN** — 同上 |
| Lint | **NOT_RUN** |
| APK 构建 | **失败** |
| 真机/模拟器主动消息验收 | **NOT_RUN** |

**注意**：这里明确写 NOT_RUN，不是「通过」。指令禁止用「编译通过 / 单元测试全绿」代替发送闭环验收。

---

## 6. 阻塞问题：AAPT 资源链接故障

### 现象

```
> Task :app:processDebugResources FAILED
ERROR: AAPT: ...\stable_resource_ids_file\...\stableIds.txt: error: failed to open: 数据无效。 (13)
```

### 时间线（重要）

- 本轮早期：同一条命令（`:app:testDebugUnitTest`、`:app:assembleDebug`）**成功执行过数十次**
- 开始写 proactive 包后：编译 `:app:compileDebugKotlin` 仍成功
- 加上 `ProactiveService` 注册到 Manifest 后：开始失败，此后**稳定复现**

### 已排除的可能（都试过，无效）

| 尝试 | 结果 |
|---|---|
| `gradle clean` | 仍失败 |
| 删除整个 `app/build` 目录 | 仍失败 |
| 删除 `C:\gh\caches\build-cache-1`（434MB） | 仍失败 |
| 腾出磁盘空间（C 盘 4.77 → 5.38 GB） | 仍失败 |
| 构建输出改到 D 盘 | 更早失败（D 盘根不可写） |
| 构建输出改到 `%LOCALAPPDATA%` | 报错路径变了，内容不变 |
| 关 `generateLocaleConfig` | 无效 |
| `--no-build-cache` / `--rerun-tasks` | 无效 |
| 恢复 `local.properties` 的 `sdk.dir` | 无效 |
| 手动创建该目录并写入测试文件 | **成功**（说明目录可写，不是权限问题） |

### 接手的人可以继续试的

1. 换 build-tools 版本：当前用 `35.0.0`（aapt2 2.19），早期成功时可能是 34.0.0
2. 完全删除 `C:\gh` 与 `C:\gradle`，重新解压一份 Gradle（怀疑我清理缓存时删掉了 AAPT 依赖的索引）
3. 把工程复制到 `C:\fm2`（不带 junction）重建，排除 junction 干扰
4. 在另一台机器 clone 该分支跑 `:app:testDebugUnitTest`，确认是否本机环境问题

### 建议

**优先在另一台机器验证。** 如果那边能过，说明是这台机器的环境被我某一步操作弄坏了，不值得为一个环境问题继续消耗时间。

---

## 7. 尚未通过的真机验收项目

全部 **NOT_RUN**：

- [ ] 不进入聊天页面，后台能生成并发布主动消息（最核心的一条）
- [ ] 连续 60 分钟高频模拟，记录候选与实际触发次数
- [ ] 不同随机种子产生不同触发分布
- [ ] 没有待办事项时仍有自主闲聊机会
- [ ] 没有历史记忆时也能产生通用闲聊
- [ ] 连续不回应时正确退避
- [ ] 用户回复后对话自然延续
- [ ] 重启后恢复待发送事项，不集中补发过期消息
- [ ] 模型失败 / 网络断开 / 通知被禁用时准确留痕
- [ ] 专注模式及免打扰期间不插话
- [ ] 通知、TTS、UI 互不成为单点故障
- [ ] 原有专注与解锁功能回归

### 真机验证步骤（构建修好后直接照做）

```bash
# 1. 装 APK 并授权（悬浮窗 + 无障碍 + 通知）
adb install -r app-debug.apk
adb shell appops set com.focusmate SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.focusmate android.permission.POST_NOTIFICATIONS

# 2. 配置模型（本机 shared_prefs，不进仓库）
#    设置页填 地址/模型名/Key -> 点「测试连接」应显示连接成功

# 3. 关键验收：不打开伙伴页，观察后台是否主动发消息
adb shell am force-stop com.focusmate
adb shell am start-foreground-service com.focusmate/.proactive.ProactiveService
adb logcat -s ProactiveEngine ProactiveService MessageDispatcher

# 4. 采集证据
adb shell dumpsys notification --noredact | grep -A3 companion_message
adb shell run-as com.focusmate cat files/proactive/state.json
```

---

## 8. 同桌和墨灵后续接入接口

目前**只预留了结构，没有实现跨工程接口**。原工程位置未确认，按指令「不要猜测包名、不要胡乱改其他工程」，没有动它们。

### 可复用的核心（已做成与 Focus Mate 解耦）

`proactive` 包里，以下 4 个类**不依赖 Android，也不依赖 Focus Mate**：

```kotlin
// 1. 纯逻辑摇骰：只需构造 Input
RandomTriggerEngine(randomSource).decide(input)

// 2. 纯逻辑情境
ContextTriggerEngine.candidates(snapshot) / pick(candidates, random)

// 3. 纯逻辑仲裁
InteractionPolicy.engagementOf(messages, now)

// 4. 配置与模式
ProactiveMode.HIGH.apply(ProactiveConfig())
```

### 需要宿主提供的（Android 侧适配）

```kotlin
// 投递必须由宿主提供两个能力，否则消息发不出去
interface ProactiveHost {
    fun notify(text: String, notificationId: Int): Boolean  // 通知
    fun speak(id: String, text: String): Boolean            // 可选 TTS
}

// 状态快照：主动引擎只读，不反向调用宿主的业务逻辑
data class HostSnapshot(
    val inFocusSession: Boolean,   // 同桌/墨灵若无专注功能，恒为 false
    val inCall: Boolean,
    val sinceFocusEndMillis: Long,
    val dueSoonTopic: String?,
    val lastCompanionWasQuestion: Boolean,
    val sinceUserSpokeMillis: Long,
    val oldTopics: List<String>,
)
```

接入步骤（给同桌/墨灵）：
1. 复制 `proactive` 包中**不依赖 Android 的 4 个类**（Config/Mode/RandomSource/RandomTriggerEngine/ContextTriggerEngine/InteractionPolicy）
2. 实现 `ProactiveHost`（它们各自的通知与语音通道）
3. 用它们的持久化替换 `ProactiveState` 的 File 实现
4. `ProactiveEngine` 的 `hostState` 注入它们自己的状态

**不要**直接复制 `ProactiveRuntime` / `ProactiveService`——那两个和 Focus Mate 的 Manifest、权限、模型配置绑死。

---

## 9. 交付清单

| 项目 | 状态 |
|---|---|
| GitHub 分支 | ✅ `feat/proactive-engine-v1` |
| 提交 SHA | ✅ `a617c1017ce464de3eadc930fbe5f660abd8f3a3` |
| PR 链接 | ✅ https://github.com/aozhenyu666-cmyk/focusmate/pull/1 |
| APK 下载链接 | ❌ **未产出**（构建失败） |
| APK SHA-256 | ❌ 无 |
| 单元测试 | ❌ NOT_RUN |
| 真机验收 | ❌ NOT_RUN |

---

## 10. 给接手者的三条建议

1. **先修构建，别再写代码。** 现在堆功能只是在没有反馈的情况下加班。构建修好、16 项测试跑通，才能判断逻辑对不对。
2. **优先验「后台不打开 UI 也能发消息」这一条。** 这是整个需求的核心，也是旧实现真正坏掉的地方。其他都是锦上添花。
3. **C 盘只剩约 5 GB。** 这个项目构建需要 2~3 GB，长期会反复出问题。清一下空间或换盘，比修代码更急。
