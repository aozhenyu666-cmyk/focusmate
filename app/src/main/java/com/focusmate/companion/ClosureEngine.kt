package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 对话收尾判断。移植自 SurfCore src/conversation-closure.js。
 *
 * 解决的是我们真实存在的一个问题：现在只要用户说话，就一定生成一条回复（每句必回）。
 * 真人不是按条数回复的，是按「这段还有没有信息要交换」回复的。
 *
 * 但这里只给**建议**，不替模型做决定，也不做硬静音：
 * - 硬静音会误杀真正想问的下一句；
 * - 纯靠提示词又会每轮都「看情况」；
 * - 所以给的是「参数 + 建议」，模型看到的是「对方最后一句像收尾 / 我最后一句像收尾 /
 *   还有未答的提问」，而不是一句「要不要回」。
 *
 * 两条硬规则（上游明确要求，我们也必须守）：
 * 1. 对方明确在提问/求助、而我还没接时，**不允许**收尾——把「不想回」和「逃避」分开；
 * 2. 收尾要留痕：结束点和下次接法都落盘，否则「结束」就真的变成「断掉」。
 */
object ClosureEngine {

    /** 消息分类。只分五类，够判断、也够解释。 */
    enum class Kind {
        /** 明确道别/结束：晚安、拜拜、先这样、去忙。 */
        ENDING,
        /** 在问问题。 */
        QUESTION,
        /** 在请求帮忙。 */
        REQUEST,
        /** 纯附和/应答：嗯、好、哈哈、收到。 */
        ACK,
        /** 有实际内容。 */
        SUBSTANTIVE,
    }

    enum class Verdict {
        /** 可以自然收尾，不必再补一句。 */
        CLOSE,
        /** 先等一等，别急着补话。 */
        LINGER,
        /** 还在聊，正常接。 */
        CONTINUE,
    }

    data class Assessment(
        val verdict: Verdict,
        val score: Double,
        val lastUserKind: Kind?,
        val lastSelfKind: Kind?,
        /** 我这边还有没有想说的 0..1。 */
        val ownIntent: Double,
        /** 对方还会不会继续 0..1。 */
        val theirIntent: Double,
        /** 话题闭环程度 0..1。 */
        val topicClosure: Double,
        /** 节奏疲劳 0..1。 */
        val fatigue: Double,
        /** 有未答的提问/请求（会硬阻止收尾）。 */
        val awaitingTheirAnswer: Boolean,
        val explicitEnd: Boolean,
        val reasons: List<String>,
    )

    private val END_ANY = listOf("晚安", "拜拜", "再见", "先这样", "先这么着", "先撤", "溜了", "去忙", "下了", "睡了", "不聊了", "回头聊", "改天聊", "下次聊", "到此为止")
    private val END_STRONG = listOf("晚安", "拜拜", "再见", "先这样", "先撤", "溜了", "去忙", "下了", "睡了", "不聊了", "回头聊", "改天聊", "下次聊")
    private val QUESTION_WORDS = listOf("吗", "嘛", "么", "咋", "怎么", "为什么", "为啥", "什么", "哪个", "哪里", "哪儿", "谁", "多少", "几点", "是不是", "有没有", "能不能", "可不可以", "要不要", "好不好", "对不对", "行不行", "在不在")
    private val REQUEST_WORDS = listOf("帮我", "帮忙", "麻烦你", "请你", "教我", "告诉我", "说说", "讲讲", "发我", "给我", "推荐", "查一下", "搜一下", "找一下", "看一下", "写一个", "弄一个")
    private val ACK_WORDS = listOf("嗯", "哦", "好", "行", "收到", "知道了", "谢谢", "感谢", "哈哈", "哈", "对", "是", "ok", "OK")

    /** 分类一句话。纯规则，可测，不依赖模型。 */
    fun classify(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.ACK
        val core = t.trimEnd('。', '！', '!', '…', '~', '～', '，', ',', ' ', '\n')
        // 明确道别：整句就是道别词（允许尾巴带语气词）。
        if (END_STRONG.any { core == it || core == it + "呀" || core == it + "啦" || core == it + "哦" }) return Kind.ENDING
        if (END_ANY.any { t.contains(it) } && t.length <= 12) return Kind.ENDING
        if (t.endsWith("?") || t.endsWith("？")) return Kind.QUESTION
        // 中文常常不打问号：「这个怎么用」「你去吗」「明天几点开始」。
        // 判据：去掉尾部语气词后以疑问词收尾；或者含明确疑问副词且句子不长。
        val coreNoTone = core.trimEnd('啊', '呀', '吧', '呢', '哦', '嘛', '啦', '哈')
        if (QUESTION_WORDS.any { core.endsWith(it) || coreNoTone.endsWith(it) }) return Kind.QUESTION
        if (t.length <= 20 && (t.contains("怎么") || t.contains("为什么") || t.contains("为啥") || t.contains("咋"))) {
            return Kind.QUESTION
        }
        if (REQUEST_WORDS.any { t.contains(it) }) return Kind.REQUEST
        // 纯附和：整句都很短且由附和词构成。
        if (t.length <= 4 && ACK_WORDS.any { t.startsWith(it) }) return Kind.ACK
        return Kind.SUBSTANTIVE
    }

    /**
     * 判断这段对话该不该收尾。
     *
     * @param recent 最近的对话（正序），含双方
     * @param pendingThoughts 我自己还有没有想说没说的话（条款）
     * @param nowMillis 当前时间
     */
    fun assess(
        recent: List<Utterance>,
        pendingThoughts: Int = 0,
        nowMillis: Long = 0L,
    ): Assessment {
        val window = 45L * 60_000L
        val scoped = recent.filter { nowMillis <= 0L || nowMillis - it.at <= window }
        val users = scoped.filter { it.role == Utterance.Role.USER }
        val selves = scoped.filter { it.role == Utterance.Role.COMPANION }
        val lastUser = users.lastOrNull()
        val lastSelf = selves.lastOrNull()

        if (lastUser == null && lastSelf == null) {
            return Assessment(Verdict.CONTINUE, 0.0, null, null, 0.0, 0.0, 0.0, 0.0, false, false, listOf("没有可判断的近期对话"))
        }

        val userKind = lastUser?.let { classify(it.text) }
        val selfKind = lastSelf?.let { classify(it.text) }

        // 我这边还想不想说
        var own = 0.0
        own += minOf(0.7, pendingThoughts * 0.35)
        if (selfKind == Kind.QUESTION) own += 0.35
        if (selfKind == Kind.ENDING) own -= 0.45
        if (selfKind == Kind.ACK && pendingThoughts == 0) own -= 0.15
        own = own.coerceIn(0.0, 1.0)

        // 对方还想不想继续
        var their = 0.15
        their = when (userKind) {
            Kind.REQUEST -> 1.0
            Kind.QUESTION -> 0.95
            Kind.SUBSTANTIVE -> 0.62
            Kind.ACK -> 0.18
            Kind.ENDING -> 0.02
            null -> 0.1
        }
        their = their.coerceIn(0.0, 1.0)

        // 话题闭环
        var closure = 0.35
        closure = when (userKind) {
            Kind.ENDING -> 1.0
            Kind.ACK -> if (selfKind == Kind.ACK || selfKind == Kind.ENDING) 0.9 else 0.62
            Kind.QUESTION, Kind.REQUEST -> 0.08
            Kind.SUBSTANTIVE -> 0.42
            null -> 0.35
        }
        closure = closure.coerceIn(0.0, 1.0)

        // 节奏疲劳：来回多、句子越回越短
        var fatigue = 0.0
        val recent10 = scoped.filter { nowMillis <= 0L || nowMillis - it.at <= 10L * 60_000L }
        if (recent10.size >= 12) fatigue += 0.3
        val tail = users.takeLast(3)
        if (tail.size >= 2) {
            val avg = tail.sumOf { it.text.length }.toDouble() / tail.size
            if (avg <= 5.0) fatigue += 0.25
        }
        val lastUserAt = lastUser?.at ?: 0L
        if (nowMillis > 0L && lastUserAt > 0L && nowMillis - lastUserAt >= 30L * 60_000L) fatigue += 0.25
        fatigue = fatigue.coerceIn(0.0, 1.0)

        // 唯一会硬阻止收尾的信号：对方在问/在求，而我还没接。
        val awaiting = (userKind == Kind.QUESTION || userKind == Kind.REQUEST) &&
            (lastSelf == null || lastSelf.at < (lastUser?.at ?: 0L))

        val explicitEnd = userKind == Kind.ENDING

        var score = 0.0
        score += closure * 0.32
        score += (1.0 - own) * 0.24
        score += (1.0 - their) * 0.22
        score += fatigue * 0.12
        if (selfKind == Kind.ACK || selfKind == Kind.ENDING) score += 0.18
        if (explicitEnd) score += 0.15
        if (awaiting) score -= 0.35
        score = score.coerceIn(0.0, 1.0)

        val verdict = when {
            awaiting -> Verdict.CONTINUE
            explicitEnd -> Verdict.CLOSE
            their >= 0.9 -> Verdict.CONTINUE
            own >= 0.6 -> Verdict.LINGER
            score >= 0.62 -> Verdict.CLOSE
            score >= 0.45 -> Verdict.LINGER
            else -> Verdict.CONTINUE
        }

        val reasons = mutableListOf<String>()
        userKind?.let { reasons += "对方最后一句：" + labelOf(it) }
        selfKind?.let { reasons += "我最后一句：" + labelOf(it) }
        if (pendingThoughts > 0) reasons += "还有 " + pendingThoughts + " 条想说没说的"
        if (awaiting) reasons += "对方明确在提问/求助，还没答"
        if (explicitEnd) reasons += "对方已经明确道别"
        if (fatigue >= 0.4) reasons += "来回节奏已经偏疲"
        if (closure >= 0.8) reasons += "话题已基本闭环"
        if (their >= 0.9) reasons += "对方明显还想继续"
        if (own >= 0.6) reasons += "你自己还有想说的"

        return Assessment(
            verdict = verdict,
            score = (score * 100).toInt() / 100.0,
            lastUserKind = userKind,
            lastSelfKind = selfKind,
            ownIntent = own,
            theirIntent = their,
            topicClosure = closure,
            fatigue = fatigue,
            awaitingTheirAnswer = awaiting,
            explicitEnd = explicitEnd,
            reasons = reasons.distinct(),
        )
    }

    fun labelOf(kind: Kind): String = when (kind) {
        Kind.ENDING -> "道别/收束"
        Kind.QUESTION -> "提问"
        Kind.REQUEST -> "请求帮忙"
        Kind.ACK -> "附和/应答"
        Kind.SUBSTANTIVE -> "有实际内容"
    }

    /** 给提示词用的一段话。只给判断与理由，不替模型决定。 */
    fun promptBlock(a: Assessment): String {
        if (a.lastUserKind == null && a.lastSelfKind == null) return ""
        val advice = when (a.verdict) {
            Verdict.CLOSE -> "可以自然收尾，不必再补一句。"
            Verdict.LINGER -> "先别急着补话；等对方补充，或过一会儿再看。"
            Verdict.CONTINUE -> "还在聊，正常接。"
        }
        val head = if (a.awaitingTheirAnswer) {
            "对方最后一句是在提问/求助，还没答——先回答，不要用收尾逃避。"
        } else {
            advice
        }
        return buildString {
            appendLine("## 这段该不该接着说")
            appendLine("- 对方最后一句：" + (a.lastUserKind?.let { labelOf(it) } ?: "暂无"))
            appendLine("- 你最后一句：" + (a.lastSelfKind?.let { labelOf(it) } ?: "暂无"))
            appendLine("- 判断：" + head)
            if (a.reasons.isNotEmpty()) appendLine("- 依据：" + a.reasons.joinToString("；"))
        }
    }
}
