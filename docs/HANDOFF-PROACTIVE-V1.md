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

## 6. 阻塞问题：AAPT 资源链接故障（✅ 已解决，根因见第 11 节）

> **2026-10-30 更新：这不是 AAPT 的问题，也不是代码问题。**
> 根因是 Gradle 缓存里那份 `aapt2.exe` 在本机无法写文件，报的「数据无效 (13)」是个假错误。
> 下面「已排除的可能」那 10 项为什么全都无效、正确的修法、以及真机验收结果，见第 11 节。
> 本节的排查时间线**保留原样**，因为它记录了错误的方向是怎么形成的。

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
| APK 下载链接 | ✅ 已产出（2026-10-30，见第 11 节） |
| APK SHA-256 | ✅ 见第 11 节 |
| 单元测试 | ✅ 192 passed / 0 failed（原 NOT_RUN） |
| 真机验收 | ⚠️ 部分通过（模拟器 API 34，见第 11 节） |

---

## 10. 给接手者的三条建议

1. **先修构建，别再写代码。** 现在堆功能只是在没有反馈的情况下加班。构建修好、16 项测试跑通，才能判断逻辑对不对。
2. **优先验「后台不打开 UI 也能发消息」这一条。** 这是整个需求的核心，也是旧实现真正坏掉的地方。其他都是锦上添花。
3. **C 盘只剩约 5 GB。** 这个项目构建需要 2~3 GB，长期会反复出问题。清一下空间或换盘，比修代码更急。


---

# 11. 2026-10-30 续：构建故障根因 + 三个致命缺陷

> 本节由接手的人在模拟器上实测后补写。**第 1～10 节的结论不删，但其中「构建故障未定位」「真机全部 NOT_RUN」两条已经过时。**

## 11.1 构建故障：根因是 aapt2 二进制写不了文件

**结论：不是 AAPT 的资源问题，不是代码问题，也不是 build 目录残留。**

排查路径（可复现）：

1. 干净 clone 到 `C:\fm2`（纯 ASCII 路径、全新 build 目录、同 SDK、同 Gradle 缓存）→ **同一个错误**。
   这一步同时排除了「代码问题」和「build 目录脏」。
2. `--info` 抓 aapt2 真实命令行，发现传的是 `--emit-ids`（**写**文件），不是 `--stable-ids`（读）。
   所以是 aapt2 **创建** `stableIds.txt` 失败，而错误文案里的「数据无效 (13)」是 Windows 残留错误码，不是真实原因。
3. 同一命令、同一路径、同一台机器，只换 aapt2 二进制：

| aapt2 来源 | 结果 |
|---|---|
| `C:\gh\caches\8.11.1\transforms\...\aapt2-8.7.3-12006047-windows\aapt2.exe` | 写不出任何文件，报「数据无效 (13)」 |
| `C:\Android\Sdk\build-tools\35.0.0\aapt2.exe` | **正常写出 13990 字节** |

4. 坏 aapt2 与路径无关：绝对路径、相对路径、目标文件预先存在，**全部失败**；
   删掉 transform 缓存让它重新解压，**仍然失败**。

**所以第 6 节那 10 项排查为什么全都无效**：它们改的是目录、缓存、磁盘空间，
而每一份重新解压出来的 aapt2 都还是坏的。文件系统本身没问题（手工写文件成功也印证了这点）。

**修法（项目代码零改动，写在全局配置里）：**

```
# C:\gh\gradle.properties   （即 GRADLE_USER_HOME/gradle.properties）
android.aapt2FromMavenOverride=C:/Android/Sdk/build-tools/35.0.0/aapt2.exe
```

**没有采用的两条路**（记下来省下一个人时间）：

- `buildToolsVersion = "34.0.0"` **无效**。AGP 8.7.3 用自己 bundle 的 aapt2，不看 SDK build-tools。
  第 6 节「可以继续试的第 1 条」是死路。
- 把工程 clone 到含中文的路径（如 `C:\Users\敖震宇\...`）会先撞 AGP 的非 ASCII 路径检查，
  报 `Your project path contains non-ASCII characters`。那是另一个问题，别和 AAPT 混为一谈。

## 11.2 构建修好后暴露的三个缺陷（都已修）

### A. `ProactiveService` 没有任何地方启动（致命）

`ProactiveRuntime.ensureService()` 是唯一启动入口，**全仓库零调用点**；
外部对 proactive 包的唯一引用是 `CompanionRuntime.kt:158` 的 `peek()`，
而 `peek()` 只返回已存在的实例——从没启动过就永远返回 null。

**后果：APK 装到手机上，服务一次都不会跑，主动功能等于没实现。**
192 项单元测试全绿也测不出来，因为测试覆盖的是纯逻辑类，没人测「接线」。

修：

- `alarm/BootReceiver.kt`：开机/覆盖安装后拉起（原本只拉了伙伴链路）。
- `ui/MainActivity.kt`：首次安装还没重启过手机时收不到开机广播，靠这里兜底。
- 两处都用独立 `runCatching`，主动链路失败不影响专注与伙伴恢复。

### B. 机会排期死锁（致命，比 A 更隐蔽）

`ProactiveEngine.tick()` 里摇骰在「机会时间检查」**之前**：

```kotlin
decision = decide()                      // 每 20 秒都摇
if (!decision.hit) { nextOpportunity = now + 6~60min }   // 机会被不断推远
if (nextOpportunity > t) return WAIT     // 于是永远成立
```

两层后果：

1. 摇骰频率被 20 秒 tick 放大成每小时上百次，「每小时 6 次机会」名存实亡；
2. **机会永远追不上**，`windowCount` 恒为 0，一条消息都发不出去。

实测证据（修复前）：`state.json` 的 `nextOpportunityAtMillis` 连续三次读取一路后移
（08:02 → 08:42 → 08:31），`windowCount` 一直是 0。

修：把「机会没到就返回」移到 `decide()` **之前**。一次机会消耗一次骰子，未中就等下一个机会。

**这个缺陷配了模型 Key 也救不了**，是「不主动」的第三个根因。

### C. 闸门拦截与未命中完全不留痕（阻塞验收）

`GATED` / `MISS` 分支不写任何日志，`logcat -s ProactiveEngine` 是一片空白，
根本分不清「服务没跑」「被免打扰拦住」「摇骰没中」。
第 7 节的验证命令让人看这个日志，实际大概率什么都看不到。

修：加 `lastSkipReason`，**原因变化时**打一行（原因不变不重复，避免每 20 秒刷屏）。

## 11.3 模拟器实测记录

环境：`emulator-5554`，`dsh_pixel_api34`（API 34），Android SDK 在 `D:\code\android-sdk`，
模型为 DeepSeek `deepseek-flash`（OpenAI 兼容，endpoint 必须填到 `/chat/completions`，客户端把它当完整 URL）。

两个环境坑（下次会再遇到）：

- 用 `D:\code\android-sdk\platform-tools\adb.exe`。C 盘那个会和它抢 daemon，导致每条命令第一条必然 `device offline`。
- 模拟器时区是 GMT。要验白天时段必须 `adb root` 后 `setprop persist.sys.timezone Asia/Shanghai`，否则一直落在 23:00–08:00 免打扰里。

实测日志（修复后）：

```
I ProactiveEngine: 未开口：GATED · 免打扰时段
I ProactiveEngine: 未开口：MISS · 未命中 p=0.41 roll=0.46
I ProactiveEngine: 未开口：WAIT · 还没到下一次机会
I ProactiveEngine: 已投递[NOTIFIED] 这天看着要下雨，我坐着坐着都有点犯困了。
```

`p=0.41` 与公式 `0.5 × 0.7(8点权重) × (1 + 0.35×0.5)` 吻合，说明概率链路正确。

`state.json` 落盘（模拟器 `run-as com.focusmate cat files/proactive/state.json`）：

```json
"trigger": "random_small_talk",
"reason": "没什么特别的事，只是想找你说句话",
"state": "notified",  "notificationId": 5000,
"generatedAt": 1793319635821, "notifiedAt": 1793319635901, "ttsAt": 1793319635909,
"sentToday": 1, "windowCount": 1
```

关键点：

- `notificationId = 5000` 是独立 ID，不是原来会互相覆盖的固定 `1003`（缺陷 8 的修复生效）；
- `random_small_talk` 证明「无记忆、无待办」时仍有兜底情境（缺陷 6 的修复生效）；
- `notifiedAt - generatedAt = 80ms`、`ttsAt - notifiedAt = 8ms`，四态分离按设计推进；
- 通知栏 `dumpsys notification` 里能看到 `pkg=com.focusmate id=5000`，截图已留档。

产物：

```
app-debug.apk   23,892,410 bytes
SHA-256         e39d838f598e95ae3942af58694ef17ca5ae36c388c30a757192d13a91e20850
```

## 11.4 验收表更新

| 原第 7 节项目 | 原状态 | 现状态 |
|---|---|---|
| 不进入聊天页面，后台能生成并发布主动消息 | NOT_RUN | **通过** |
| 没有待办事项时仍有自主闲聊机会 | NOT_RUN | **通过**（`random_small_talk`） |
| 没有历史记忆时也能产生通用闲聊 | NOT_RUN | **通过**（全新安装无记忆） |
| 不同随机种子产生不同触发分布 | NOT_RUN | **通过**（单元测试 16 项） |
| 通知、TTS、UI 互不成为单点故障 | NOT_RUN | **部分**：通知与 TTS 独立推进时间戳；TTS 是否真发声未听 |
| 重启后恢复待发送事项，不集中补发过期消息 | NOT_RUN | 未验 |
| 连续 60 分钟高频模拟，记录候选与实际触发次数 | NOT_RUN | 未验 |
| 连续不回应时正确退避 | NOT_RUN | 未验 |
| 用户回复后对话自然延续 | NOT_RUN | 未验 |
| 模型失败 / 网络断开 / 通知被禁用时准确留痕 | NOT_RUN | **部分**：代码路径有 `模型失败：` 与 `未配置模型，跳过` 两种留痕，未逐个制造故障验证 |
| 专注模式及免打扰期间不插话 | NOT_RUN | **通过**：两种闸门都实测到 `GATED`（免打扰 / 专注进行中） |
| 原有专注与解锁功能回归 | NOT_RUN | 未验 |

## 11.5 已发现但**未修**的问题

1. **配置不持久化**：`ProactiveEngine.config` 只在内存里，`updateConfig()` 只 `persist()` 状态，
   而 `ProactiveState` 不包含 config。进程重启后所有配置回默认值。
   是否要修取决于之后有没有 UI 入口——现在 proactive 没有设置界面，等于只能用默认值。
2. **proactive 没有任何 UI 入口**：开关、模式、频率都改不了，用户只能接受默认（DAILY / 每小时 6 次 / 23:00–08:00）。
   如果产品要求可调，这块要补界面，同时必须一起修上面那条。
3. 第 7 节的验证命令 `am start-foreground-service com.focusmate/.proactive.ProactiveService`
   **是错的**：服务是 `exported="false"`，adb shell 会报 `Requires permission not exported`。
   正确做法是通过打开 APP（`am start -n com.focusmate/.ui.MainActivity`）让它自己拉起。
4. `ProactiveService` 的 `onStartCommand` 会经 `ProactiveRuntime.engine()` 连带启动 `CompanionService`，
   两个前台服务在同一时刻启动。目前工作正常，但没有针对性验证厂商省电策略下的表现。

## 11.6 本轮改动清单

| 文件 | 改动 | 性质 |
|---|---|---|
| `alarm/BootReceiver.kt` | +7 | 开机恢复时拉起主动引擎 |
| `ui/MainActivity.kt` | +5 | 首次安装后兜底拉起 |
| `proactive/ProactiveEngine.kt` | +40/-6 | 排期死锁修复 + 诊断留痕 |
| `gradle.properties`（全局 `C:\gh\`，**不入库**） | +1 | aapt2 覆盖 |

单元测试：**192 passed / 0 failed**（原 176 既有 + 16 新增）。

