package com.focusmate.proactive

import android.content.Context
import android.util.Log
import com.focusmate.companion.ModelClient
import com.focusmate.companion.ModelResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主动引擎编排器。把「摇骰 -> 选情境 -> 生成 -> 投递」串起来。
 *
 * 与 UI 完全解耦：只要这个对象被构造，即使一次都没打开过伙伴页，
 * 也能生成并发出通知（这是本次最关键的设计目标）。
 *
 * 顺序上守两条：
 * 1. 先闸门后概率再生成（便宜的先做，避免白烧 token）；
 * 2. 先生成落盘再通知（崩溃后知道发过什么，不会重复发）。
 */
class ProactiveEngine(
    private val context: Context,
    private val modelClient: ModelClient,
    private val now: () -> Long = { System.currentTimeMillis() },
    random: RandomSource = SystemRandomSource(),
    /** 用哪套提示词/人格。默认用内置的通用闲聊模板。 */
    private val promptBuilder: (ContextTriggerEngine.Candidate, ProactiveState) -> Pair<String, String> = CompanionProactivePrompt::build,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val store = FileProactiveStore(File(context.filesDir, "proactive/state.json"))
    private val notifier = com.focusmate.notify.Notifier(context)
    private val randomEngine = RandomTriggerEngine(random)
    private val random = random
    private val workScope = scope

    var config: ProactiveConfig = ProactiveConfig()
        private set

    /** 外部注入的宿主状态，避免直接依赖专注模块。 */
    var hostState: () -> HostSnapshot = { HostSnapshot.EMPTY }

    /** 投递器。speak 由宿主决定是否给（没有 TTS 也能跑）。 */
    private val dispatcher = MessageDispatcher(
        context = context,
        store = store,
        notifier = notifier,
        now = now,
        speak = { id, text -> hostSpeak(id, text) },
    )

    /** 宿主是否愿意接语音。默认不接。 */
    var hostSpeak: (String, String) -> Boolean = { _, _ -> false }

    /** 观测回调：只用于日志/测试，不参与投递。 */
    var onEvent: ((String) -> Unit)? = null

    private var state: ProactiveState = ProactiveState()
    private var inFlight: Job? = null

    /** 宿主快照：主动引擎只读这些，不反向调用专注逻辑。 */
    data class HostSnapshot(
        val inFocusSession: Boolean,
        val inCall: Boolean,
        val sinceFocusEndMillis: Long,
        val dueSoonTopic: String?,
        val lastCompanionWasQuestion: Boolean,
        val sinceUserSpokeMillis: Long,
        val oldTopics: List<String>,
    ) {
        companion object {
            val EMPTY = HostSnapshot(
                inFocusSession = false,
                inCall = false,
                sinceFocusEndMillis = Long.MAX_VALUE,
                dueSoonTopic = null,
                lastCompanionWasQuestion = false,
                sinceUserSpokeMillis = Long.MAX_VALUE,
                oldTopics = emptyList(),
            )
        }
    }

    fun load(): ProactiveState {
        state = store.read() ?: ProactiveState()
        store.lastError?.let { emit("状态读取失败：" + it) }
        return state
    }

    fun current(): ProactiveState = state

    fun updateConfig(next: ProactiveConfig) {
        config = next
        persist()
    }

    /**
     * 走一次完整判定。由前台服务每秒/每几秒调用。
     * @return 本次是否真的发出了消息
     */
    fun tick(): TickResult {
        val t = now()
        state = rollDay(t)
        val host = hostState()

        // 结算一次「上一条没回应」。退避据此生效。
        if (InteractionPolicy.shouldCountAsSilence(state.lastSentAtMillis, t)) {
            val lastAnswered = state.recent.lastOrNull()?.state == MessageState.READ
            if (!lastAnswered && state.recent.isNotEmpty()) {
                state = dispatcher.markUnanswered(state)
                emit("用户未回应，沉默计数 -> " + state.silenceStreak)
            }
        }

        val (winStart, winCount) = InteractionPolicy.rollWindow(
            state.windowStartMillis, state.windowCount, t, config,
        )
        if (winStart != state.windowStartMillis) state = state.copy(windowStartMillis = winStart, windowCount = winCount)

        val hour = localHour(t)
        val minuteOfDay = localMinuteOfDay(t)
        val input = RandomTriggerEngine.Input(
            nowMillis = t,
            hour = hour,
            minuteOfDay = minuteOfDay,
            config = config,
            sinceLastSentMillis = if (state.lastSentAtMillis <= 0) Long.MAX_VALUE else t - state.lastSentAtMillis,
            sentToday = state.sentToday,
            sentInWindow = state.windowCount,
            silenceStreak = state.silenceStreak,
            engagement = InteractionPolicy.engagementOf(state.recent, t),
            inFocusSession = host.inFocusSession,
            inCall = host.inCall,
            importantFollowUpDueSoon = host.dueSoonTopic != null,
        )

        val decision = randomEngine.decide(input)
        if (!decision.hit) {
            // 未命中/被拦：排下一次机会，然后什么都不做。不调模型。
            state = state.copy(nextOpportunityAtMillis = randomEngine.nextOpportunityMillis(input))
            persist()
            return TickResult(decision.verdict.name, decision.reason, false)
        }

        // 命中：确认还没到下一次机会时间（避免同一秒连续触发）。
        if (state.nextOpportunityAtMillis > t) {
            return TickResult("WAIT", "还没到下一次机会", false)
        }

        // 选情境。没有候选就不说话（不硬凑）。
        val candidates = ContextTriggerEngine.candidates(
            ContextTriggerEngine.Snapshot(
                nowMillis = t,
                followUpDueSoonTopic = host.dueSoonTopic,
                lastCompanionWasQuestion = host.lastCompanionWasQuestion,
                sinceFocusEndMillis = host.sinceFocusEndMillis,
                sinceUserSpokeMillis = host.sinceUserSpokeMillis,
                oldTopics = host.oldTopics,
                recentlySaid = state.recent.map { it.text },
            ),
        )
        val chosen = ContextTriggerEngine.pick(candidates, random)
        if (chosen == null) {
            state = state.copy(nextOpportunityAtMillis = randomEngine.nextOpportunityMillis(input))
            persist()
            return TickResult("NO_CANDIDATE", "没有可说的情境", false)
        }

        // 生成并投递。串行：同一时刻只允许一次。
        if (inFlight != null) return TickResult("BUSY", "上一次还在生成", false)
        val issuedState = state
        inFlight = workScope.launch {
            try {
                val (system, user) = promptBuilder(chosen, issuedState)
                val result = modelClient.complete(system, user)
                when (result) {
                    is ModelResult.Ok -> {
                        val text = result.text.trim().take(300)
                        if (text.isBlank()) {
                            emit("模型返回空文本，放弃本次")
                        } else if (isTooSimilar(text, issuedState)) {
                            emit("与最近说过的话太像，放弃本次：" + text.take(20))
                        } else {
                            val (nextState, msg) = dispatcher.dispatch(
                                state = state,
                                text = text,
                                trigger = chosen.type.name.lowercase(),
                                reason = chosen.reason,
                            )
                            state = nextState.copy(
                                nextOpportunityAtMillis = randomEngine.nextOpportunityMillis(
                                    input.copy(nowMillis = now()),
                                ),
                            )
                            persist()
                            emit("已投递[" + msg.state + "] " + text.take(30))
                        }
                    }
                    is ModelResult.Failed -> {
                        emit("模型失败：" + result.reason)
                        state = state.copy(
                            nextOpportunityAtMillis = now() + config.retryMillis,
                        )
                        persist()
                    }
                    ModelResult.Unconfigured -> emit("未配置模型，跳过")
                }
            } catch (t: Throwable) {
                emit("生成异常：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                inFlight = null
            }
        }
        return TickResult("GENERATING", chosen.reason, true)
    }

    data class TickResult(val verdict: String, val reason: String, val fired: Boolean)

    /** 用户回了消息：标记已读并抬升互动度。 */
    fun onUserReplied() {
        val last = state.recent.lastOrNull { it.state == MessageState.NOTIFIED || it.state == MessageState.SHOWN }
        state = if (last != null) dispatcher.markRead(state, last.id) else state.copy(
            silenceStreak = 0,
            engagement = (state.engagement + 0.1).coerceAtMost(1.0),
        )
        persist()
    }

    /** 页面打开时把最近的通知标成「已展示」（仍然不是已读）。 */
    fun onUiOpened() {
        val ids = state.recent.filter { it.state == MessageState.NOTIFIED }.map { it.id }
        if (ids.isNotEmpty()) {
            state = dispatcher.markShown(state, ids)
            persist()
        }
    }

    private fun isTooSimilar(text: String, s: ProactiveState): Boolean =
        s.recent.takeLast(10).any { ContextTriggerEngine.similar(it.text, text) }

    private fun rollDay(t: Long): ProactiveState {
        val key = dayKey(t)
        val (k, n) = InteractionPolicy.ensureDay(state.dayKey, key, state.sentToday)
        return if (k != state.dayKey || n != state.sentToday) state.copy(dayKey = k, sentToday = n) else state
    }

    private fun persist() {
        runCatching { store.write(state) }.onFailure { Log.w(TAG, "写入失败: " + it.message) }
    }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        onEvent?.invoke(msg)
    }

    private fun localHour(t: Long): Int =
        java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)

    private fun localMinuteOfDay(t: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun dayKey(t: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val m = c.get(java.util.Calendar.MONTH) + 1
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        return c.get(java.util.Calendar.YEAR).toString() + "-" + (if (m < 10) "0" else "") + m + "-" + (if (d < 10) "0" else "") + d
    }

    companion object { private const val TAG = "ProactiveEngine" }
}
