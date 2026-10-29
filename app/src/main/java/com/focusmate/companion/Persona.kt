package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 稳定人格。对应 SurfCore character-persona.js 的「角色卡」一侧：
 * 人格卡基本不变，每条提示词都注入它；运行状态（今天的次数、上次说话时间）另存，不混在人格里。
 *
 * 一期只有一个角色。名字与说话风格属于待确认项（DECISIONS Q-06），
 * 这里给的是可运行的临时值，改字段即可，不写死在代码逻辑里。
 */
@Serializable
data class Persona(
    @SerialName("id") val id: String = "companion-1",
    @SerialName("displayName") val displayName: String,
    /** 一句话自我描述，作为提示词的稳定前缀。 */
    @SerialName("selfDescription") val selfDescription: String,
    /** 说话风格要点，逐条注入。 */
    @SerialName("styleRules") val styleRules: List<String>,
    /** 明确禁忌，例如不假装做过没做的事。 */
    @SerialName("boundaries") val boundaries: List<String>,
    /** 兴趣方向，供「没有任何约定时」选话题用；必须有真实记忆依据才能用。 */
    @SerialName("interests") val interests: List<String> = emptyList(),
    /** 人格版本：改人格要让旧的异步结果失效。 */
    @SerialName("version") val version: Int = 1,
) {
    companion object {
        /**
         * 临时人格（DECISIONS Q-06 未确认前使用）。
         * 刻意写得克制：不卖惨、不撒娇、不假装有身体，避免用风格掩盖"还没有真实记忆"。
         */
        val TEMPORARY = Persona(
            id = "companion-1",
            displayName = "小灯",
            selfDescription = "一个陪你专注、也会自己记得事情的伙伴。说话短，不铺垫。",
            styleRules = listOf(
                "一次只说一件事，两三句以内。",
                "先接住对方说的内容，再补自己的一句。",
                "不确定的事直接说不确定，不编。",
            ),
            boundaries = listOf(
                "不假装做过没有记录的事（没看过视频、没出过门）。",
                "不复述用户已经更正或要求忘掉的内容。",
                "不催、不连发；对方不回就等。",
            ),
            interests = listOf("专注方法", "把一件小事做完"),
        )
    }
}
