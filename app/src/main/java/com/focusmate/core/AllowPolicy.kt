package com.focusmate.core

/**
 * 前台判定的唯一入口。
 *
 * 这里刻意不判断「微信里是不是在通话」：按包名判定是稳定、可测的，
 * 按界面判定依赖无障碍节点结构、随时可能变。若用户要的是「微信只允许通话界面」，
 * 那是另一档能力，必须先说明差异再改，不能在这里偷偷放宽或收紧。
 */
class AllowPolicy(
    private val target: String,
    private val exceptions: Set<String>,
    private val systemUi: SystemUiClassifier = SystemUiClassifier(),
    /** 与目标等价的包名，见 FocusSession.targetAliases。 */
    private val targetAliases: Set<String> = emptySet(),
) {
    enum class Verdict {
        /** 目标应用：正常使用。 */
        TARGET,

        /** 白名单内的例外应用（通话、微信等）：允许，但会话仍在守护。 */
        EXCEPTION,

        /** 系统界面 / 输入法 / 权限框 / 本 APP 自己：不当作离开。 */
        NEUTRAL,

        /** 不该进的应用：拦截。 */
        BLOCK,
    }

    fun classify(packageName: String?, selfPackage: String): Verdict {
        if (packageName.isNullOrBlank()) return Verdict.NEUTRAL
        if (packageName == selfPackage) return Verdict.NEUTRAL
        if (packageName == target || packageName in targetAliases) return Verdict.TARGET
        if (packageName in exceptions) return Verdict.EXCEPTION
        if (systemUi.isNeutral(packageName)) return Verdict.NEUTRAL
        return Verdict.BLOCK
    }
}

/**
 * 系统界面识别。这一层必须保守：只放行确定不算「离开专注」的包，
 * 不能因为无法识别就默认放行，否则守护形同虚设。
 */
class SystemUiClassifier(
    private val explicit: Set<String> = DEFAULT_NEUTRAL,
) {
    fun isNeutral(packageName: String): Boolean {
        if (packageName in explicit) return true
        // 输入法普遍以 .inputmethod / .ime 结尾；桌面与系统设置由下面这些前缀覆盖。
        if (packageName.contains(".inputmethod")) return true
        if (packageName.endsWith(".ime")) return true
        return false
    }

    companion object {
        val DEFAULT_NEUTRAL: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.server.telecom",
            "com.android.launcher3",
            "com.android.internal.systemui.navbar.gestural",
        )
    }
}
