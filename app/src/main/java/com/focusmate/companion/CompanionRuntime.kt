package com.focusmate.companion

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/**
 * 主动伙伴的运行时。和 GuardRuntime 的关系是**并列、互不依赖**：
 * 专注限制与恢复不经过这里，模型/网络/TTS 全挂也不影响它（执行指令 §0 第 6 条、任务 2.4-I）。
 *
 * 一条完整的链（对应执行指令 §7 任务 2.2）：
 *   对话/约定 -> 产生候选事项 -> 检查依据与规则 -> 持久保存 -> 到期调度
 *   -> 加载人格与相关记忆 -> 生成 -> 投递 -> 按回应完成/取消/改期
 *
 * 并发控制：
 * - 同一时刻只允许一次投递（对应 dsh-world 的 `delivery ??=`）；
 * - 生成前后各校验一次 version（DeliveryGate）；
 * - 所有延迟回调都带 followUpId + version，旧的不认。
 */
class CompanionRuntime(
    private val context: Context,
    private val modelClient: ModelClient,
    private val speech: SpeechOutput,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val store = FileCompanionStore(File(context.filesDir, "companion/state.json"))
    private var state: CompanionState = CompanionState()
    private var delivering: Job? = null

    /** 界面观察用。 */
    var onChanged: ((CompanionState) -> Unit)? = null
    /** 把一句主动发言交给界面/通知展示。文字先展示，语音可选。 */
    var onUtterance: ((Utterance, DeliveryTrace) -> Unit)? = null
    /** 失败提示（例如状态读取失败、模型不可用）。 */
    var onProblem: ((String) -> Unit)? = null

    /** 最近的投递记录（只保留最后 30 条，供界面/复查用；不做"已读"推断）。 */
    private val traces = ArrayDeque<DeliveryRecord>()
    fun recentDeliveries(): List<DeliveryRecord> = traces.toList()

    private fun recordTrace(t: DeliveryTrace) {
        traces.addLast(t.snapshot())
        while (traces.size > 30) traces.removeFirst()
    }

    val persona: Persona = Persona.TEMPORARY

    /**
     * 是否正在专注。刻意做成注入的函数而不是直接引用 GuardRuntime：
     * 伙伴模块与限制模块必须能各自单独测试，也要能在伙伴模块里"没有专注功能"时正常运行。
     */
    var focusActive: () -> Boolean = { false }

    /**
     * 出站审查的统一入口。返回 null 表示整句都不该发。
     * 已知密钥从 ModelSettings 取当前配置的那一个——我们本来就知道它是什么，
     * 所以用精确匹配，不靠正则在裸串里瞎找（那会大面积误伤）。
     */
    private fun guardOutbound(text: String): String? {
        if (OutboundGuard.shouldDropEntirely(text)) return null
        val secrets = runCatching { ModelSettings.load(context).config.apiKey }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: emptyList()
        val result = OutboundGuard.inspect(text, secrets)
        if (result.redacted) {
            onProblem?.invoke("出站审查改写了这一句：" + result.reasons.joinToString("；"))
        }
        return result.text
    }

    fun load() {
        val loaded = store.read()
        if (loaded != null) {
            // 旧数据迁移（R-01）：把 sourceRef 里能明确解析的 "utterance:<id>"
            // 恢复成来源关联，否则升级前的事实遗忘后仍会泄漏原句。
            val (migrated, changed) = FactMigration.migrate(loaded)
            state = migrated
            if (changed) persist()
        } else if (store.lastError != null) {
            // 读坏不能当成"没有状态"：那会让旧事项悄悄消失。
            onProblem?.invoke(store.lastError!!)
            state = CompanionState()
        }
        state = ensureToday(state)
        persist()
        publish()
    }

    fun current(): CompanionState = state

    /**
     * 更正一条事实（界面按钮用）。旧条目转 SUPERSEDED 并指向新的，
     * 上下文只从 ACTIVE 拼，所以旧内容不会复活。
     */
    fun correctFact(factId: String, newText: String): Boolean {
        if (newText.isBlank()) return false
        // 走带来源的版本：旧句子也要一并退出模型输入（F-02）。
        val result = FactEngine.correctWithSource(state, factId, newText.trim(), now()) ?: return false
        state = result.first
        persist(); publish()
        return true
    }

    /** 忘记一条事实（界面按钮用）。保留墓碑，不物理删除（见 DECISIONS D-15）。 */
    fun forgetFact(factId: String): Boolean {
        // 走带来源的版本：原句 + 以它为唯一依据的事项一起停用（F-02）。
        val next = FactEngine.forgetWithSource(state, factId, now()) ?: return false
        state = next
        persist(); publish()
        return true
    }

    /** 开关主动闲聊。UI 必须有这个入口，否则验收用例 J 无法由用户自行触发。 */
    fun setAllowSmallTalk(enabled: Boolean): Boolean {
        state = state.copy(
            config = state.config.copy(allowSmallTalk = enabled),
            memoryVersion = state.memoryVersion + 1,
        )
        persist(); publish()
        return true
    }

    /** 调整频率与免打扰（保守默认允许被用户改动，改动会留痕）。 */
    fun updateSchedule(
        gapMinMinutes: Int? = null,
        gapMaxMinutes: Int? = null,
        dailyMax: Int? = null,
        quietStartMinute: Int? = null,
        quietEndMinute: Int? = null,
    ): Boolean {
        var c = state.config
        gapMinMinutes?.let { c = c.copy(gapMinMillis = it.coerceAtLeast(5) * 60_000L) }
        gapMaxMinutes?.let { c = c.copy(gapMaxMillis = it.coerceAtLeast(5) * 60_000L) }
        dailyMax?.let { c = c.copy(dailyMax = it.coerceIn(0, 24)) }
        quietStartMinute?.let { c = c.copy(quietStartMinute = it.coerceIn(0, 1439)) }
        quietEndMinute?.let { c = c.copy(quietEndMinute = it.coerceIn(0, 1439)) }
        state = state.copy(config = c, memoryVersion = state.memoryVersion + 1)
        persist(); publish()
        return true
    }

    /** 手动排一条主动事项（界面用；必须有依据，这是引擎层的硬约束）。 */
    fun scheduleFollowUp(topic: String, evidence: String, dueAtMillis: Long): Boolean {
        if (topic.isBlank() || evidence.isBlank()) return false
        val (next, _) = FollowUpEngine.userAgreement(state, topic.trim(), evidence.trim(), dueAtMillis, now())
        state = next
        persist(); publish()
        return true
    }

    /** 取消一条事项（界面用）。 */
    fun cancelFollowUp(id: String): Boolean {
        val next = FollowUpEngine.cancel(state, id, now()) ?: return false
        state = next
        persist(); publish()
        return true
    }

    /** 改期一条事项（界面用）。版本 +1，旧的异步结果自动失效。 */
    fun rescheduleFollowUp(id: String, dueAtMillis: Long): Boolean {
        val next = FollowUpEngine.reschedule(state, id, dueAtMillis, now()) ?: return false
        state = next
        persist(); publish()
        return true
    }

    private fun persist(): Boolean = try {
        store.write(state)
        true
    } catch (t: Throwable) {
        onProblem?.invoke("伙伴状态保存失败：" + (t.message ?: t.javaClass.simpleName))
        false
    }

    private fun publish() {
        onChanged?.invoke(state)
    }

    /** 本地日期键，用于跨天重置今日次数。 */
    private fun dayKeyOf(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return c.get(Calendar.YEAR).toString() + "-" + (if (m < 10) "0" else "") + m + "-" + (if (d < 10) "0" else "") + d
    }

    private fun minuteOfDay(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun ensureToday(s: CompanionState): CompanionState {
        val key = dayKeyOf(now())
        return if (s.dayKey != key) s.copy(dayKey = key, countToday = 0) else s
    }

    // ---------------------------------------------------------------- 对话

    /**
     * 用户说话。这是**唯一**的对话入口：解析意图 -> 落事实 -> 回一句 -> 结算事项。
     * @param onReply 把回复交给界面（已含文字；语音在内部触发）
     */
    fun userSays(text: String, onReply: (String) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val t = now()
        state = ensureToday(state)

        // 1) 落对话
        val userUtterance = Utterance(
            id = "u-" + t,
            role = Utterance.Role.USER,
            text = trimmed,
            at = t,
        )
        state = state.copy(
            dialogue = (state.dialogue + userUtterance).takeLast(80),
            // 用户又开口了：上次收尾的软冷却立刻失效（结束不是把人关掉）。
            closure = ClosurePolicy.reopen(state.closure, t),
            lastUserSpeakAtMillis = t,
            epoch = state.epoch + 1,
            // 又说了新话 = 记忆变了：正在生成的那条主动发言必须失效（F-05）。
            memoryVersion = state.memoryVersion + 1,
        )

        // 2) 结构化意图（改期/取消/改锁定请求）
        val openTopic = state.followUps.firstOrNull { it.open }?.topic
        val intent = IntentParser.parse(trimmed, t, openTopic)
        when (intent?.kind) {
            IntentParser.Kind.LOCK_CHANGE_REQUEST -> {
                // 明确拒绝：改锁定必须走独立流程（DECISIONS D-03）。
                val reply = "锁定时长不能在聊天里改。要改的话，请回到首页那个专注入口重新设。"
                appendCompanion(reply, t, proactive = false, followUpId = null)
                onReply(reply)
                persist(); publish()
                return
            }
            IntentParser.Kind.CANCEL -> {
                val open = state.followUps.filter { it.open }
                open.forEach { state = FollowUpEngine.cancel(state, it.id, t) ?: state }
                val reply = if (open.isEmpty()) "现在没有排着的提醒。" else "好，不提醒了。"
                appendCompanion(reply, t, proactive = false, followUpId = null)
                onReply(reply)
                persist(); publish()
                return
            }
            IntentParser.Kind.POSTPONE -> {
                val due = intent.dueAtMillis ?: (t + 10 * 60_000L)
                val topic = intent.topic ?: "刚才说的事"
                val (next, _) = FollowUpEngine.userAgreement(
                    state = state,
                    topic = topic,
                    evidence = "用户说「" + intent.evidence + "」",
                    dueAtMillis = due,
                    nowMillis = t,
                    evidenceUtteranceId = userUtterance.id,
                )
                state = next
                val reply = "行，" + clockText(due) + " 再聊这个。"
                appendCompanion(reply, t, proactive = false, followUpId = null)
                onReply(reply)
                persist(); publish()
                return
            }
            null -> Unit
        }

        // 3) 普通对话：把用户的话记成事实（来源=用户说的），并做复习
        if (shouldRecordAsFact(trimmed)) {
            val (next, _) = FactEngine.add(
                state = state,
                text = trimmed,
                source = FactSource.USER_STATED,
                sourceRef = "utterance:" + userUtterance.id,
                nowMillis = t,
                sourceUtteranceId = userUtterance.id,
            )
            state = FactEngine.touch(next, trimmed, t)
        }
        // 用户回应之后，同话题的主动事项视为已处理，避免回完又冒一句。
        state = DeliveryGate.closeOnUserReply(state, t)

        // 4) 收尾判断（只做建议，不硬静音）
        val assessment = ClosureEngine.assess(
            recent = state.dialogue.takeLast(20),
            pendingThoughts = state.followUps.count { it.open },
            nowMillis = t,
        )
        // 对方明确在提问/求助且还没答时，不允许收尾——先回答（上游的 hard gate）。
        val closureBlocked = assessment.awaitingTheirAnswer

        // 5) 生成回复
        val followUpForContext = state.followUps.firstOrNull { it.open }
        val bundle = CompanionContext.build(persona, state, t, trimmed, followUpForContext, assessment)
        val issuedMemoryVersion = bundle.memoryVersion
        scope.launch {
            when (val result = modelClient.complete(bundle.systemPrompt, bundle.userPrompt)) {
                is ModelResult.Ok -> {
                    // 生成期间又说了话/又记了新事实：这份回复的上下文已经过期，丢弃。
                    if (state.memoryVersion != issuedMemoryVersion) {
                        onProblem?.invoke("对话在你回复期间有更新，这一条已丢弃")
                        return@launch
                    }
                    // 出站审查：Key、本机路径、凭据赋值都不能说出去。
                    val guarded = guardOutbound(result.text.take(300))
                    if (guarded == null) {
                        onProblem?.invoke("这一句触发了出站审查，已拦下未发送")
                        return@launch
                    }
                    val reply = guarded
                    appendCompanion(reply, now(), proactive = false, followUpId = null)
                    // 判断为可以收尾时留一条记录：收在哪、下次怎么接。
                    if (assessment.verdict == ClosureEngine.Verdict.CLOSE && !closureBlocked) {
                        state = state.copy(
                            closure = ClosurePolicy.make(
                                assessment = assessment,
                                summary = reply,
                                nextOpening = "",
                                nowMillis = now(),
                            ),
                            epoch = state.epoch + 1,
                            memoryVersion = state.memoryVersion + 1,
                        )
                    }
                    onReply(reply)
                    persist(); publish()
                    speakAsync(reply)
                }
                ModelResult.Unconfigured -> {
                    val reply = "（还没配好模型，我现在只能记下来，不能好好回应。）"
                    appendCompanion(reply, now(), proactive = false, followUpId = null)
                    onReply(reply)
                    persist(); publish()
                }
                is ModelResult.Failed -> {
                    // 模型故障不能影响专注限制；这里只如实说明。
                    onProblem?.invoke("模型调用失败：" + result.reason)
                    val reply = "（我这边连不上模型，先把你说的记下了。）"
                    appendCompanion(reply, now(), proactive = false, followUpId = null)
                    onReply(reply)
                    persist(); publish()
                }
            }
        }
    }

    /** 只有"像一条可复用信息"的话才入事实库，避免把寒暄也存成记忆。 */
    private fun shouldRecordAsFact(text: String): Boolean {
        if (text.length < 6) return false
        val trivial = listOf("好的", "嗯嗯", "在吗", "哈哈", "好", "行", "收到")
        return trivial.none { text == it }
    }

    private fun appendCompanion(text: String, at: Long, proactive: Boolean, followUpId: String?) {
        val u = Utterance(
            id = (if (proactive) "p-" else "c-") + at,
            role = Utterance.Role.COMPANION,
            text = text,
            at = at,
            proactive = proactive,
            followUpId = followUpId,
        )
        state = state.copy(dialogue = (state.dialogue + u).takeLast(80), epoch = state.epoch + 1)
        if (proactive) onUtterance?.invoke(u, DeliveryTrace(followUpId, state.epoch.toInt()))
    }

    private fun speakAsync(text: String) {
        speech.speak("u" + now(), text)
    }

    private fun clockText(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    // ---------------------------------------------------------------- 主动调度

    /**
     * 每次 tick 调一次。返回本次决策，便于界面/日志显示"为什么没说"。
     * 到期判断、规则判断全在这里，不散落到 Service。
     */
    fun tick(): SchedulePolicy.Decision {
        val t = now()
        state = ensureToday(state)
        state = FollowUpEngine.pruneExpired(state, t, state.config)
        val decision = SchedulePolicy.evaluate(
            nowMillis = t,
            config = state.config,
            minuteOfDay = ::minuteOfDay,
            lastRunAtMillis = state.lastRunAtMillis,
            countToday = state.countToday,
            nextDueAtMillis = state.nextDueAtMillis,
            lastUserSpeakAtMillis = state.lastUserSpeakAtMillis,
            hasOpenFollowUp = FollowUpEngine.hasOpen(state),
            inFocusSession = focusActive(),
        )
        // 每次 tick 之后都重排闹钟，并且**必须按策略算**（R-04）：
        // 免打扰/专注中/关闭/次数上限时不能把过去的时刻排进去，否则闹钟一响就立即再响。
        CompanionScheduler.scheduleAt(
            context,
            WakePlanner.plan(
                state = state,
                nowMillis = t,
                minuteOfDay = ::minuteOfDay,
                inFocusSession = focusActive(),
            ),
        )
        if (decision is SchedulePolicy.Decision.Run) {
            val due = FollowUpEngine.dueNow(state, t)
            if (due != null && delivering == null) {
                deliver(due)
            } else if (due == null && state.config.allowSmallTalk) {
                maybeSmallTalk(t)
            }
        }
        persist()
        publish()
        return decision
    }

    /**
     * 真正的投递。用 delivering 保证串行（对应 dsh-world 的 delivery ??=）；
     * 生成前后各校验一次版本（DeliveryGate）。
     */
    private fun deliver(item: FollowUp) {
        val t = now()
        val begun = FollowUpEngine.beginDelivery(state, item.id, t) ?: return
        state = begun.first
        val deliveringItem = begun.second
        // 发起时的两个版本快照：事项版本 + 记忆版本。任何一个在生成期间变了，结果都作废。
        val issuedVersion = deliveringItem.version
        val issuedMemoryVersion = state.memoryVersion
        persist(); publish()

        delivering = scope.launch {
            try {
                // ---- 生成前校验
                val preCheck = DeliveryGate.check(
                    followUpId = item.id,
                    issuedVersion = issuedVersion,
                    issuedMemoryVersion = issuedMemoryVersion,
                    state = state,
                    nowMillis = now(),
                    nowAllowsSpeaking = stillAllowedToSpeak(),
                )
                if (preCheck is DeliveryGate.Verdict.Drop) {
                    // 丢弃通常意味着"用户已改期/取消/改了记忆"：那不是失败，
                    // 不能再记成 FAILED（会让用户看到莫名其妙的失败，还会盖掉刚设的新约定）。
                    recordTrace(trace(item.id, issuedVersion).also { it.discard(preCheck.reason) })
                    state = FollowUpEngine.releaseDelivery(state, item.id, now(), issuedVersion) ?: state
                    persist(); publish()
                    return@launch
                }

                val bundle = CompanionContext.build(persona, state, now(), item.topic, deliveringItem)
                val result = modelClient.complete(bundle.systemPrompt, bundle.userPrompt)

                // ---- 输出前再校验一次（生成期间可能发生一切）
                val postCheck = DeliveryGate.check(
                    followUpId = item.id,
                    issuedVersion = issuedVersion,
                    issuedMemoryVersion = bundle.memoryVersion,
                    state = state,
                    nowMillis = now(),
                    nowAllowsSpeaking = stillAllowedToSpeak(),
                )
                if (postCheck is DeliveryGate.Verdict.Drop) {
                    recordTrace(trace(item.id, issuedVersion).also { it.discard(postCheck.reason) })
                    if (result is ModelResult.Failed) {
                        onProblem?.invoke("主动发言失败：" + result.reason)
                    }
                    state = FollowUpEngine.releaseDelivery(state, item.id, now(), issuedVersion) ?: state
                    persist(); publish()
                    return@launch
                }

                when (result) {
                    is ModelResult.Ok -> {
                        val text = result.text.take(300)
                        val tr = trace(item.id, issuedVersion)
                        tr.generated(now(), text)
                        appendCompanion(text, now(), proactive = true, followUpId = item.id)
                        recordTrace(tr)
                        // 说出去之后是 AWAITING_REPLY，不是 DONE：等用户回应才算这件事了结（F-03）。
                        val sent = FollowUpEngine.markSent(
                            state = state,
                            id = item.id,
                            nowMillis = now(),
                            config = state.config,
                            minuteOfDay = ::minuteOfDay,
                            roll = 0.5,
                            issuedVersion = issuedVersion,
                        )
                        if (sent == null) {
                            onProblem?.invoke("事项版本已变，本次发言未记录为已发出")
                        } else {
                            state = sent
                        }
                        persist(); publish()
                        speakAsync(text)
                    }
                    ModelResult.Unconfigured -> {
                        onProblem?.invoke("未配置模型，主动发言跳过")
                        markFailedSafely(item.id, issuedVersion, "未配置模型")
                    }
                    is ModelResult.Failed -> {
                        onProblem?.invoke("主动发言失败：" + result.reason)
                        markFailedSafely(item.id, issuedVersion, result.reason)
                    }
                }
            } finally {
                delivering = null
            }
        }
    }

    /** 失败记账统一入口：带发起版本，晚到的失败不会复活已取消/已改期的事项（F-04）。 */
    private fun markFailedSafely(id: String, issuedVersion: Int, reason: String) {
        val next = FollowUpEngine.markFailed(
            state = state,
            id = id,
            nowMillis = now(),
            config = state.config,
            reason = reason,
            issuedVersion = issuedVersion,
        ) ?: state
        state = next
        persist(); publish()
    }

    /**
     * 此刻是否仍允许开口。用于输出前的最后一次检查：
     * 生成开始时允许、结束时已经进入专注/免打扰/关掉开关，就不该再播出去（F-05 后半）。
     */
    private fun stillAllowedToSpeak(): Boolean = SchedulePolicy.evaluate(
        nowMillis = now(),
        config = state.config,
        minuteOfDay = ::minuteOfDay,
        lastRunAtMillis = state.lastRunAtMillis,
        countToday = state.countToday,
        nextDueAtMillis = 0L, // 到点与否不参与：这里问的是"规则允不允许"
        lastUserSpeakAtMillis = state.lastUserSpeakAtMillis,
        hasOpenFollowUp = true,
        inFocusSession = focusActive(),
    ) is SchedulePolicy.Decision.Run

    /** 主动闲聊：只能从真实旧话题/事实里生成候选，没有依据就不说（DECISIONS D-05 默认关闭）。 */
    private fun maybeSmallTalk(t: Long) {
        val seed = FactEngine.recall(state, "", limit = 3).firstOrNull() ?: return
        val topic = seed.text.take(30)
        val created = FollowUpEngine.personaInterest(
            state = state,
            topic = topic,
            evidence = "记忆里的：" + seed.text.take(40),
            dueAtMillis = t,
            nowMillis = t,
        ) ?: return
        state = created.first
        persist()
        deliver(created.second)
    }

    private fun trace(followUpId: String?, issuedVersion: Int) = DeliveryTrace(followUpId, issuedVersion)

    fun shutdown() {
        delivering?.cancel()
        delivering = null
        scope.cancel()
        speech.shutdown()
    }
}
