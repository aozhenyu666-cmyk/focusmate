package com.focusmate.companion

/**
 * 投递结果的记账。执行指令 §7 任务 2.3 要求分别记录：
 *   生成完成 / 文字展示或通知发布 / TTS 开始 / TTS 完成 / TTS 失败
 *
 * 并且明确：
 * - 通知发布 != 用户已读；
 * - TTS 完成 != 用户听到了。
 * 所以这里只记录"我们做到了哪一步"，不做任何"已送达/已读"的推断。
 */
data class DeliveryRecord(
    val followUpId: String?,
    val issuedVersion: Int,
    val generatedAt: Long? = null,
    val generatedText: String? = null,
    val shownAt: Long? = null,
    val ttsStartedAt: Long? = null,
    val ttsFinishedAt: Long? = null,
    val ttsError: String? = null,
    val discardedReason: String? = null,
)

/** 单条投递的状态推进。任何一步都可能不发生，发生时才有时间戳。 */
class DeliveryTrace(private val followUpId: String?, private val issuedVersion: Int) {
    var generatedAt: Long? = null; private set
    var generatedText: String? = null; private set
    var shownAt: Long? = null; private set
    var ttsStartedAt: Long? = null; private set
    var ttsFinishedAt: Long? = null; private set
    var ttsError: String? = null; private set
    var discardedReason: String? = null; private set

    fun generated(now: Long, text: String) { generatedAt = now; generatedText = text }
    fun shown(now: Long) { shownAt = now }
    fun ttsStarted(now: Long) { ttsStartedAt = now }
    fun ttsFinished(now: Long) { ttsFinishedAt = now }
    fun ttsFailed(reason: String) { ttsError = reason }
    fun discard(reason: String) { discardedReason = reason }

    fun snapshot(): DeliveryRecord = DeliveryRecord(
        followUpId = followUpId,
        issuedVersion = issuedVersion,
        generatedAt = generatedAt,
        generatedText = generatedText,
        shownAt = shownAt,
        ttsStartedAt = ttsStartedAt,
        ttsFinishedAt = ttsFinishedAt,
        ttsError = ttsError,
        discardedReason = discardedReason,
    )
}
