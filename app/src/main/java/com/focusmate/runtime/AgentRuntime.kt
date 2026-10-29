package com.focusmate.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.focusmate.agent.AgentCore
import com.focusmate.agent.AgentLoop
import com.focusmate.agent.AgentPrefs
import com.focusmate.agent.AgentState
import com.focusmate.agent.AgentStore
import com.focusmate.agent.Agenda
import com.focusmate.agent.AgendaKind
import com.focusmate.agent.AgendaStatus
import com.focusmate.agent.ChatEntry
import com.focusmate.agent.FocusInfo
import com.focusmate.agent.FocusMode
import com.focusmate.agent.Gate
import com.focusmate.agent.LlmException
import com.focusmate.agent.LlmMessage
import com.focusmate.agent.LocalClock
import com.focusmate.agent.Origin
import com.focusmate.agent.ProactivePolicy
import com.focusmate.agent.Prompt
import com.focusmate.agent.ProposalStatus
import com.focusmate.agent.Role
import com.focusmate.agent.Situation
import com.focusmate.agent.Tools
import com.focusmate.agent.Turn
import com.focusmate.agent.proactive
import com.focusmate.alarm.AgentAlarm
import com.focusmate.core.Enforcement
import com.focusmate.core.FocusSession
import com.focusmate.core.Presets
import com.focusmate.core.SessionKind
import com.focusmate.core.SessionState
import com.focusmate.core.SessionStates
import com.focusmate.net.OpenAiHttpClient
import com.focusmate.notify.AgentNotifier
import com.focusmate.platform.AgentSettings
import com.focusmate.platform.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 伙伴运行时的唯一协调点。约束：
 * 1. 只有这里改伙伴状态；每次修改在 stateLock 内完成并立刻存盘。
 * 2. 模型调用一次只跑一轮（brainLock），后一轮能看到前一轮的结果。
 * 3. 主动轮次生成期间用户先开口了，结果作废，不再发出（版本检查）。
 * 4. 提醒不依赖模型；模型、网络、语音任何一个坏了，专注锁照常工作。
 * 5. 专注相关的话只根据 GuardRuntime 的真实状态迁移说，不根据模型的猜测。
 */
object AgentRuntime {

    data class Status(
        val thinking: Boolean = false,
        val error: String? = null,
        val storeError: String? = null,
        val lastOkAtMillis: Long = 0,
    )

    private sealed interface TurnResult {
        data class Spoke(val entry: ChatEntry) : TurnResult
        data object Silent : TurnResult
        data object Stale : TurnResult
        data class Failed(val message: String, val kind: LlmException.Kind?) : TurnResult
    }

    private val installed = AtomicBoolean(false)
    private lateinit var app: Context
    private lateinit var store: AgentStore
    private lateinit var settings: AgentSettings
    private lateinit var notifier: AgentNotifier
    private lateinit var alarm: AgentAlarm
    @Volatile private var speaker: Speaker? = null
    private val main = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Mutex()
    private val brainLock = Mutex()
    /** 到期处理同一时刻只跑一份：闹钟、开机、打开 APP 可能同时触发，避免同一条提醒发两次。 */
    private val dueLock = Mutex()

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile var prefs: AgentPrefs = AgentPrefs()
        private set
    @Volatile private var uiVisible = false

    private val _keyConfigured = MutableStateFlow(false)
    val keyConfigured: StateFlow<Boolean> = _keyConfigured.asStateFlow()

    /** 专注会话 → 用户在提议里说的目标。只用于描述，丢了也不影响限制本身。 */
    private val goals = ConcurrentHashMap<String, String>()

    private val llm = OpenAiHttpClient {
        val p = prefs
        OpenAiHttpClient.Config(p.baseUrl, p.model, settings.apiKey())
    }

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        app = context.applicationContext
        store = AgentStore(File(app.filesDir, "agent/state.json"))
        settings = AgentSettings(app)
        notifier = AgentNotifier(app)
        alarm = AgentAlarm(app)
        prefs = settings.load()
        _state.value = store.read()
        _keyConfigured.value = settings.hasApiKey()
        store.lastError?.let { e -> _status.update { it.copy(storeError = e) } }
        GuardRuntime.install(app)
        GuardRuntime.addTransitionListener { old, new -> onGuardTransition(old, new) }
        if (prefs.speakAloud) ensureSpeaker()
        reschedule()
    }

    fun notifier(): AgentNotifier = notifier

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun clock() = LocalClock(zone())
    private fun core() = AgentCore(clock()) { UUID.randomUUID().toString().replace("-", "").take(8) }
    private fun now() = System.currentTimeMillis()

    private suspend fun <T> mutate(f: (AgentState) -> Pair<AgentState, T>): T = stateLock.withLock {
        val (next, result) = f(_state.value)
        try {
            store.write(next)
            if (_status.value.storeError != null) _status.update { it.copy(storeError = null) }
        } catch (t: Throwable) {
            _status.update { it.copy(storeError = "保存失败：${t.javaClass.simpleName}: ${t.message}") }
        }
        _state.value = next
        result
    }

    private suspend fun update(f: (AgentState) -> AgentState) = mutate { f(it) to Unit }

    // ------------------------------------------------------------------ 用户说话

    fun sendUser(text: String, fromNotification: Boolean = false) {
        scope.launch { sendUserAndWait(text, fromNotification) }
    }

    suspend fun sendUserAndWait(text: String, fromNotification: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        mutate { core().addUser(it, t, if (fromNotification) Origin.NOTIFICATION_REPLY else Origin.CHAT, now()) }
        reschedule()
        when (val r = runTurn(Turn.Reply)) {
            is TurnResult.Failed -> update { core().addEvent(it, "没能回复：${r.message}", now()) }
            is TurnResult.Spoke -> speakIfAllowed(r.entry)
            else -> Unit
        }
        if (fromNotification || !uiVisible) notifier.postConversation(prefs.name, _state.value.chat)
        reschedule()
    }

    /** 通知里刚点了发送：先把「对方正在想」显示出来，避免通知一直转圈。 */
    fun showWaitingInNotification(text: String) {
        val temp = ChatEntry("pending", Role.USER, text, now())
        notifier.postConversation(prefs.name, _state.value.chat + temp, waiting = true)
    }

    // ------------------------------------------------------------------ 一轮模型调用

    private suspend fun runTurn(turn: Turn): TurnResult = brainLock.withLock {
        val started = now()
        val snapshot = _state.value
        _status.update { it.copy(thinking = true) }
        try {
            val messages = Prompt.build(prefs, snapshot, focusInfo(), turn, started, zone())
            val ctx = Tools.Ctx(started, zone(), snapshot.facts.map { it.id }.toSet(), turn.proactive)
            val outcome = withTimeout(TURN_TIMEOUT_MILLIS) { AgentLoop(llm).run(messages, turn, ctx, prefs.name) }

            if (turn.proactive && _state.value.proactive.lastUserAtMillis > started) return@withLock TurnResult.Stale
            if (turn == Turn.Reply && outcome.text == null && outcome.actions.isEmpty()) {
                return@withLock fail("模型没有给出回复", LlmException.Kind.BAD_RESPONSE)
            }
            val entry = mutate { core().commit(it, turn, outcome, now()) }
            _status.update { it.copy(error = null, lastOkAtMillis = now()) }
            if (entry != null) TurnResult.Spoke(entry) else TurnResult.Silent
        } catch (e: LlmException) {
            fail(e.message, e.kind)
        } catch (e: TimeoutCancellationException) {
            fail("模型 ${TURN_TIMEOUT_MILLIS / 1000} 秒内没回完", LlmException.Kind.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail("内部错误：${t.javaClass.simpleName}: ${t.message}", null)
        } finally {
            _status.update { it.copy(thinking = false) }
        }
    }

    private fun fail(message: String, kind: LlmException.Kind?): TurnResult.Failed {
        _status.update { it.copy(error = message) }
        return TurnResult.Failed(message, kind)
    }

    // ------------------------------------------------------------------ 到期事项与主动开口

    /** 闹钟、开机、打开 APP 都走这里。到点做什么只看持久状态。 */
    suspend fun processDue() = dueLock.withLock {
        val start = now()
        for (due in Agenda.due(_state.value.agenda, start)) {
            val item = _state.value.agenda.firstOrNull { it.id == due.id && it.status == AgendaStatus.PENDING } ?: continue
            val t = now()
            if (Agenda.isStale(item, t)) {
                update { it.copy(agenda = Agenda.markDropped(it.agenda, item.id, t, "迟到太久，不再提起"), revision = it.revision + 1) }
                continue
            }
            if (item.kind == AgendaKind.REMINDER) {
                val e = mutate { core().deliverReminder(it, item, now()) }
                notifier.postReminder(prefs.name, e)
                speakIfAllowed(e)
                continue
            }
            when (val g = ProactivePolicy.gate(item, situation(t), _state.value.proactive, prefs, clock())) {
                is Gate.Later -> update { it.copy(agenda = Agenda.defer(it.agenda, item.id, g.atMillis, g.why), revision = it.revision + 1) }
                Gate.Now -> handleProactiveItem(item)
            }
        }
        maybeSpontaneous(force = false)
        reschedule()
    }

    private suspend fun handleProactiveItem(item: com.focusmate.agent.AgendaItem) {
        val turn = if (item.kind == AgendaKind.FOLLOW_UP) Turn.FollowUp(item) else Turn.Review(item)
        when (val r = runTurn(turn)) {
            is TurnResult.Spoke -> deliverProactive(r.entry)
            TurnResult.Silent -> Unit
            TurnResult.Stale -> update {
                it.copy(agenda = Agenda.markDropped(it.agenda, item.id, now(), "你先开口了，这次不另外提"), revision = it.revision + 1)
            }
            is TurnResult.Failed -> {
                val exhausted = r.kind == LlmException.Kind.NO_KEY || mutate {
                    val (list, out) = Agenda.markFailed(it.agenda, item.id, now(), r.message)
                    it.copy(agenda = list, revision = it.revision + 1) to out
                }
                if (exhausted) {
                    if (item.kind == AgendaKind.FOCUS_REVIEW) {
                        deliverProactive(mutate { core().deliverFallbackReview(it, item, now()) })
                    } else {
                        update { it.copy(agenda = Agenda.markDropped(it.agenda, item.id, now(), "生成失败：${r.message}"), revision = it.revision + 1) }
                    }
                }
            }
        }
    }

    private suspend fun maybeSpontaneous(force: Boolean) {
        if (!settings.hasApiKey()) return
        val t = now()
        val st = _state.value.proactive
        if (!force) {
            if (ProactivePolicy.nextSpontaneousAt(t - 60_000L, st, prefs, clock()) > t) return
            if (ProactivePolicy.spontaneousBlocker(situation(t), st, prefs, clock()) != null) return
        }
        when (val r = runTurn(Turn.Spontaneous)) {
            is TurnResult.Spoke -> deliverProactive(r.entry)
            TurnResult.Silent -> update { it.copy(proactive = ProactivePolicy.afterSpontaneousSkipped(it.proactive, now(), prefs, failed = false)) }
            is TurnResult.Failed -> update { it.copy(proactive = ProactivePolicy.afterSpontaneousSkipped(it.proactive, now(), prefs, failed = true)) }
            TurnResult.Stale -> Unit
        }
    }

    /** APP 在前台时补处理一次到期事项。 */
    fun kick() {
        scope.launch { processDue() }
    }

    /** 「现在聊一句」：跳过间隔，但仍走同一条生成与投递路径。 */
    fun chatNow() {
        scope.launch {
            maybeSpontaneous(force = true)
            reschedule()
        }
    }

    private fun deliverProactive(e: ChatEntry) {
        if (!uiVisible) notifier.postConversation(prefs.name, _state.value.chat)
        speakIfAllowed(e)
    }

    /** 只排一个闹钟：最近的到期事项或下一次可以闲聊的时刻。 */
    fun reschedule() {
        if (!installed.get()) return
        val st = _state.value
        val t = now()
        val next = listOfNotNull(
            Agenda.nextDueAt(st.agenda),
            if (settings.hasApiKey()) ProactivePolicy.nextSpontaneousAt(t, st.proactive, prefs, clock()) else null,
        ).minOrNull()
        if (next == null) alarm.cancel() else alarm.set(maxOf(next, t + 1_000L))
    }

    fun nextWakeAt(): Long? {
        val st = _state.value
        return listOfNotNull(
            Agenda.nextDueAt(st.agenda),
            if (settings.hasApiKey()) ProactivePolicy.nextSpontaneousAt(now(), st.proactive, prefs, clock()) else null,
        ).minOrNull()
    }

    fun spontaneousBlocker(): String? =
        if (!settings.hasApiKey()) "还没有填 API Key"
        else ProactivePolicy.spontaneousBlocker(situation(now()), _state.value.proactive, prefs, clock())

    // ------------------------------------------------------------------ 专注锁

    fun focusInfo(): FocusInfo {
        val s = GuardRuntime.session()
        return if (s != null && SessionStates.isActive(s.state)) {
            FocusInfo(true, "进行中：${describe(s)}，${Prompt.clock(s.expiresAtMillis, zone())} 结束", s.expiresAtMillis)
        } else {
            FocusInfo(false, "没有在专注")
        }
    }

    private fun situation(t: Long): Situation {
        val f = focusInfo()
        return Situation(t, f.active, f.endsAtMillis)
    }

    private fun describe(s: FocusSession): String {
        val minutes = (s.plannedDurationMillis / 60_000L).toInt()
        val kind = when (s.kind) {
            SessionKind.GEMINI_DOCK -> "沉浸舱（只用 Gemini）"
            SessionKind.DEEP_FOCUS -> "深度专注（只留本 APP）"
            SessionKind.SINGLE_APP -> "单应用专注"
        }
        val goal = goals[s.id]?.let { "，目标：$it" }.orEmpty()
        return "$kind $minutes 分钟$goal"
    }

    /** 只根据专注锁的真实状态迁移说话。重复或迟到的迁移不会产生第二条。 */
    private fun onGuardTransition(old: FocusSession?, new: FocusSession?) {
        if (old == null || new == null || old.id != new.id || old.state == new.state) return
        scope.launch {
            val desc = describe(new)
            val tag = "focus:${new.id}:${new.state.name}"
            if (_state.value.chat.any { it.agendaId == tag }) return@launch
            val text = when (new.state) {
                SessionState.ENFORCING -> if (old.state == SessionState.PREPARING) "专注已开始（已核验）：$desc，${Prompt.clock(new.expiresAtMillis, zone())} 结束" else null
                SessionState.COMPLETED -> "专注按时结束，限制已解除（已核验）：$desc"
                SessionState.USER_ENDED -> {
                    val ran = ((now() - new.startedAtMillis) / 60_000L).coerceAtLeast(0)
                    "专注被提前结束（进行了 $ran 分钟）：$desc"
                }
                SessionState.FAILED -> "专注没能按预期执行：${new.failure?.detail ?: "原因未知"}"
                else -> null
            } ?: return@launch
            update { core().addEvent(it, text, now(), tag) }
            if (new.state == SessionState.COMPLETED || new.state == SessionState.USER_ENDED) {
                update { core().scheduleReview(it, desc, now()) }
                reschedule()
            }
        }
    }

    /** 用户在提议卡片上点了开始。必须在主线程调用（专注锁要启动其它应用）。 */
    fun acceptProposal(proposalId: String) {
        val p = core().findProposal(_state.value, proposalId) ?: return
        if (p.status != ProposalStatus.PENDING) return
        val t = now()
        var error: String? = null
        val session: FocusSession? = when (p.mode) {
            FocusMode.GEMINI -> {
                val pkg = Presets.GEMINI_CANDIDATES.firstOrNull { installed(it) }
                if (pkg == null) {
                    error = "手机上没找到 Gemini"
                    null
                } else {
                    Presets.geminiDock(pkg, t, Enforcement.OVERLAY_PLUS_A11Y, durationMillis = p.minutes * 60_000L)
                }
            }
            FocusMode.DEEP -> Presets.deepFocus(app.packageName, p.minutes * 60_000L, t, Enforcement.OVERLAY_PLUS_A11Y)
        }
        if (session != null) {
            goals[session.id] = p.goal
            GuardRuntime.startSession(session) { error = it }
        }
        val err = error
        scope.launch {
            update { core().setProposal(it, proposalId, if (err == null) ProposalStatus.ACCEPTED else ProposalStatus.FAILED, err) }
            if (err != null) update { core().addEvent(it, "专注没能开始：$err", now()) }
        }
    }

    fun declineProposal(proposalId: String) {
        scope.launch { update { core().setProposal(it, proposalId, ProposalStatus.DECLINED, null) } }
    }

    private fun installed(pkg: String): Boolean = try {
        app.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ 界面与设置

    fun onUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) notifier.clearConversation()
    }

    fun quietFor(millis: Long) {
        scope.launch {
            update { core().setQuiet(it, now() + millis) }
            notifier.clearConversation()
            reschedule()
        }
    }

    fun endQuiet() {
        scope.launch {
            update { core().setQuiet(it, 0) }
            reschedule()
        }
    }

    fun cancelAgenda(id: String) {
        scope.launch {
            update { core().cancelAgenda(it, id, now()) }
            reschedule()
        }
    }

    fun deleteFact(id: String) {
        scope.launch { update { core().deleteFact(it, id) } }
    }

    fun clearChat() {
        scope.launch { update { it.copy(chat = emptyList(), revision = it.revision + 1) } }
    }

    fun savePrefs(p: AgentPrefs) {
        settings.save(p)
        prefs = settings.load()
        if (prefs.speakAloud) ensureSpeaker() else speaker?.stop()
        reschedule()
    }

    fun setApiKey(value: String?) {
        settings.setApiKey(value)
        _keyConfigured.value = settings.hasApiKey()
        reschedule()
    }

    fun apiKeyHint(): String? = settings.apiKeyHint()

    fun hasApiKey(): Boolean = settings.hasApiKey()

    fun canExactAlarm(): Boolean = alarm.canExact()

    fun notificationsEnabled(): Boolean = notifier.enabled()

    fun speakerStatus(): String = speaker?.status ?: "未开启"

    /** 设置页「测试连接」：不带工具发一句，返回模型原话或错误原因。 */
    suspend fun testConnection(): String = try {
        val r = withTimeout(30_000L) {
            llm.complete(listOf(LlmMessage.System("只回复两个字：通了"), LlmMessage.User("测试")), emptyList())
        }
        "连接正常：" + (r.content ?: "（空回复）")
    } catch (e: LlmException) {
        "失败：" + e.message
    } catch (e: TimeoutCancellationException) {
        "失败：30 秒没有响应"
    }

    private fun ensureSpeaker() {
        if (speaker != null) return
        main.post { if (speaker == null) speaker = Speaker(app) }
    }

    private fun speakIfAllowed(e: ChatEntry) {
        if (!prefs.speakAloud) return
        if (ProactivePolicy.isQuietHours(now(), prefs, clock())) return
        val s = speaker ?: run { ensureSpeaker(); return }
        main.post { s.speak(e.text) }
    }

    private const val TURN_TIMEOUT_MILLIS = 75_000L
}
