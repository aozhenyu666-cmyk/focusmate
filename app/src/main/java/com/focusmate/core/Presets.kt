package com.focusmate.core

import java.util.UUID

/**
 * 会话预设。产品语义在这里固化，UI 与调度都从这里取，避免各处硬编码。
 */
object Presets {
    /**
     * Gemini 沉浸舱：用户点按钮 → 切到 Gemini 页面 → 约十分钟内只允许 Gemini，
     * 另外保留通话与微信（用户明确要求）。
     *
     * 注意：这里的「微信」按包名放行，等于微信整体可用；如果之后要收紧成
     * 「只允许微信通话界面」，必须改 AllowPolicy 并重新验收，不能在这里悄悄改。
     */
    const val DOCK_DURATION_MILLIS: Long = 10L * 60L * 1000L

    val GEMINI_CANDIDATES: List<String> = listOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
    )

    val CALL_PACKAGES: List<AllowedApp> = listOf(
        AllowedApp("com.android.dialer", "系统电话", AllowReason.CALL),
        AllowedApp("com.google.android.dialer", "电话", AllowReason.CALL),
        AllowedApp("com.android.server.telecom", "通话服务", AllowReason.CALL),
        AllowedApp("com.android.incallui", "通话界面", AllowReason.CALL),
    )

    val WECHAT: AllowedApp = AllowedApp("com.tencent.mm", "微信", AllowReason.EXCEPTION)

    fun geminiDock(
        geminiPackage: String,
        nowMillis: Long,
        enforcement: Enforcement,
        includeWeChat: Boolean = true,
        durationMillis: Long = DOCK_DURATION_MILLIS,
    ): FocusSession {
        val exceptions = buildList {
            addAll(CALL_PACKAGES)
            if (includeWeChat) add(WECHAT)
        }
        return FocusSession(
            id = UUID.randomUUID().toString(),
            kind = SessionKind.GEMINI_DOCK,
            ruleVersion = 1,
            targetPackage = geminiPackage,
            exceptions = exceptions,
            startedAtMillis = nowMillis,
            expiresAtMillis = nowMillis + durationMillis,
            plannedDurationMillis = durationMillis,
            state = SessionState.PREPARING,
            enforcement = enforcement,
            owned = OwnedRestrictions(),
        )
    }

    fun singleApp(
        targetPackage: String,
        durationMillis: Long,
        nowMillis: Long,
        enforcement: Enforcement,
    ): FocusSession = FocusSession(
        id = UUID.randomUUID().toString(),
        kind = SessionKind.SINGLE_APP,
        ruleVersion = 1,
        targetPackage = targetPackage,
        exceptions = CALL_PACKAGES,
        startedAtMillis = nowMillis,
        expiresAtMillis = nowMillis + durationMillis,
        plannedDurationMillis = durationMillis,
        state = SessionState.PREPARING,
        enforcement = enforcement,
        owned = OwnedRestrictions(),
    )
}
