package com.focusmate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一期只需一个有效专注会话。所有字段都是本 APP 自己的记录，
 * 不代表系统状态；系统状态以 enforcement.v erified 为准。
 */
@Serializable
data class FocusSession(
    @SerialName("id") val id: String,
    /** 会话类型：专注某个 App，还是「和 Gemini 谈十分钟」的沉浸舱。 */
    @SerialName("kind") val kind: SessionKind,
    /** 本会话的规则版本，每次改规则加一，用于让旧异步结果失效。 */
    @SerialName("ruleVersion") val ruleVersion: Int,
    /** 用户明确选中的目标应用（沉浸舱里就是 Gemini）。 */
    @SerialName("targetPackage") val targetPackage: String,
    /** 本次允许通过的其它应用，例如沉浸舱里的电话与微信。 */
    @SerialName("exceptions") val exceptions: List<AllowedApp>,
    @SerialName("startedAtMillis") val startedAtMillis: Long,
    @SerialName("expiresAtMillis") val expiresAtMillis: Long,
    /** 开始时写入的时长，用于展示与核对，不用于计算（计算只用 expiresAtMillis）。 */
    @SerialName("plannedDurationMillis") val plannedDurationMillis: Long,
    @SerialName("state") val state: SessionState,
    @SerialName("enforcement") val enforcement: Enforcement,
    /** 当前这条会话「已经挂上去」的限制记录；解除时只处理这些。 */
    @SerialName("owned") val owned: OwnedRestrictions,
    @SerialName("failure") val failure: Failure? = null,
)

@Serializable
enum class SessionKind {
    /** 阶段 1：用户选一个非关键 App，验证限制本身。 */
    @SerialName("single_app") SINGLE_APP,

    /** 沉浸舱：打开 Gemini，约十分钟内只允许 Gemini + 通话 + 微信。 */
    @SerialName("gemini_dock") GEMINI_DOCK,
}

@Serializable
enum class Enforcement {
    /** 只有覆盖层，用户可自行关闭。能力弱，不能对外称强限制。 */
    @SerialName("overlay") OVERLAY,

    /** 无障碍前台识别 + 覆盖层拦截，用户不能靠返回键退出覆盖层。 */
    @SerialName("overlay_plus_a11y") OVERLAY_PLUS_A11Y,
}

@Serializable
data class AllowedApp(
    /** 包名。 */
    @SerialName("packageName") val packageName: String,
    /** 只给人看的名字，不参与判定。 */
    @SerialName("label") val label: String,
    /** 为什么允许：目标、通话、约定例外。 */
    @SerialName("reason") val reason: AllowReason,
)

@Serializable
enum class AllowReason {
    @SerialName("target") TARGET,
    @SerialName("call") CALL,
    @SerialName("exception") EXCEPTION,
    @SerialName("system") SYSTEM,
}

/**
 * 本 APP 实际持有的限制记录。解除时只按这里的内容还原，
 * 不能无差别解除其它来源（例如用户自己在设置里挂起的应用）。
 */
@Serializable
data class OwnedRestrictions(
    /** 已挂上覆盖层的包名，用于确保不重复添加、不误删。 */
    @SerialName("overlayFor") val overlayFor: List<String> = emptyList(),
    /** 本次是否曾请求过系统级挂起（一期默认关闭，留字段以便如实记录）。 */
    @SerialName("suspendRequested") val suspendRequested: List<String> = emptyList(),
)

/** 失败与未知必须能表达，不能沿成功路径继续。 */
@Serializable
data class Failure(
    @SerialName("code") val code: FailureCode,
    @SerialName("detail") val detail: String,
    @SerialName("atMillis") val atMillis: Long,
)

@Serializable
enum class FailureCode {
    /** 无障碍服务没连上，无法识别前台。 */
    @SerialName("a11y_unavailable") A11Y_UNAVAILABLE,
    /** 悬浮窗权限缺失，覆盖层加不上。 */
    @SerialName("overlay_permission_missing") OVERLAY_PERMISSION_MISSING,
    /** 覆盖层 addView 抛异常。 */
    @SerialName("overlay_add_failed") OVERLAY_ADD_FAILED,
    /** 目标应用启动被系统拒绝。 */
    @SerialName("target_launch_failed") TARGET_LAUNCH_FAILED,
    /** 到期时执行权限不可用，无法确认已解除。 */
    @SerialName("release_unverified") RELEASE_UNVERIFIED,
    /** 状态存盘失败。 */
    @SerialName("persist_failed") PERSIST_FAILED,
    /** 现状与记录不一致，需要用户确认。 */
    @SerialName("state_conflict") STATE_CONFLICT,
}
