package com.focusmate.companion

/**
 * 把内部状态翻译成用户能看懂的一句话。
 *
 * 为什么单独一层：要求是「不要把内部枚举、版本号、异常堆栈直接显示给普通用户」。
 * 之前界面上会出现 A11Y_UNAVAILABLE、v1 · 会话 e9eb2a、in-focus 这类字符串——
 * 对开发者有用，对用户只是噪音。这里统一翻译，并且能被单测钉住。
 */
object CompanionStatus {

    /** 主动交流当前为什么不说 / 什么时候会说。 */
    fun describe(
        state: CompanionState,
        nowMillis: Long,
        minuteOfDay: Int,
        inFocusSession: Boolean,
    ): String {
        val config = state.config

        if (!config.enabled) return "主动交流已关闭。你可以随时找我说话。"

        // 顺序与 SchedulePolicy.evaluate 保持一致，避免两处说法打架。
        if (inFocusSession) return "专注结束后再找你。"

        if (SchedulePolicy.inQuietHours(minuteOfDay, config)) {
            return "现在是免打扰时间（" + clock(config.quietStartMinute) + " 到 " + clock(config.quietEndMinute) + "）。"
        }

        if (config.dailyMax > 0 && state.countToday >= config.dailyMax) {
            return "今天的主动联系次数已用完（" + config.dailyMax + " 次），明天再说。"
        }

        if (ClosurePolicy.suppressesProactive(state.closure, nowMillis)) {
            return "刚才那段聊完了，过一会儿再找你。"
        }

        val open = state.followUps.filter { it.open && it.status != FollowUpStatus.AWAITING_REPLY }
        if (open.isEmpty()) {
            return "还没有待跟进的约定。跟我说「十分钟后问问我进度」就能建一个。"
        }

        val next = open.minByOrNull { it.dueAtMillis }
        if (next != null) {
            val whenText = if (next.dueAtMillis <= nowMillis) "现在" else relativeTime(next.dueAtMillis - nowMillis)
            return "准备在" + whenText + "跟你聊「" + next.topic + "」。"
        }
        return "有 " + open.size + " 件事准备跟进。"
    }

    /** 剩余时长说人话：不显示毫秒、不显示时间戳。 */
    private fun relativeTime(deltaMillis: Long): String {
        val minutes = (deltaMillis / 60_000L).coerceAtLeast(1L)
        return when {
            minutes < 60L -> minutes.toString() + " 分钟后"
            minutes < 60L * 24L -> (minutes / 60L).toString() + " 小时后"
            else -> (minutes / (60L * 24L)).toString() + " 天后"
        }
    }

    private fun clock(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    /** 专注会话状态的人话版本。内部枚举不给用户看。 */
    fun focusLabel(stateName: String): String = when (stateName.lowercase()) {
        "preparing" -> "正在进入目标应用"
        "enforcing" -> "专注进行中"
        "away_allowed" -> "在允许的应用里"
        "blocking" -> "已拦住其他应用"
        "releasing" -> "正在解除"
        "completed" -> "已结束并恢复"
        "user_ended" -> "已由你结束"
        "failed" -> "执行失败，需要处理"
        else -> "状态未知"
    }

    /** 失败原因的人话版本。只翻译已知码，未知的原样返回——宁可显示原文也不瞎猜。 */
    fun failureLabel(code: String, detail: String): String = when (code.lowercase()) {
        "a11y_unavailable" -> "无障碍服务断了，限制已停止。重新开启后再开始专注。"
        "overlay_permission_missing" -> "缺少悬浮窗权限，没法拦住其他应用。"
        "overlay_add_failed" -> "拦截层没能显示出来，限制没有生效。"
        "target_launch_failed" -> "没能打开目标应用：" + detail
        "release_unverified" -> "到点了但没能确认限制已解除，请手动检查。"
        "persist_failed" -> "状态没保存成功，请检查手机存储空间。"
        "state_conflict" -> "当前状态和记录对不上，请重新开始一次专注。"
        else -> if (detail.isBlank()) "原因不明" else detail
    }
}
