package com.focusmate.companion

/**
 * 出站内容审查：模型要说的每一句话，在展示/播报之前都先过这一层。
 *
 * 移植自 SurfCore src/sensitive.js 的思路，但按 Android 场景重写：
 * 那边拦的是 QQ 消息里的本机路径与凭据；我们这边情况更糟——
 * 上下文里就有 API Key（ModelSettings 存在 shared_prefs），
 * 模型完全可能在被追问时把 Key、把本机绝对路径原样吐出来。
 *
 * 两个设计要点（沿用上游踩过的教训）：
 * 1. 不做全局关键词封杀。「密钥」「password」这类词正常对话里也会出现，
 *    一律打码会把正常句子弄坏。只有带赋值形态（token=xxx、密码是xxx）才判敏感。
 * 2. 不靠正则抓裸密钥。32 位十六进制串在别的场景里到处都是，
 *    加进正则会大面积误伤。已知密钥走精确匹配（我们本来就知道自己的 key 是什么）。
 */
object OutboundGuard {

    private const val QUOTE = "'\""
    /** 正则里表示「一个反斜杠」需要两个字符；Kotlin 源码因此要写四个。 */
    private const val BACKSLASH = "\\\\"
    private const val WS = "\\s"

    /** 盘符路径、UNC 路径、以及常见的 unix 家目录。 */
    private val PATH_RE: Regex = run {
        val bs = BACKSLASH
        val notSpace = "[^" + WS + QUOTE + "<>|]"
        val drive = "(?<![A-Za-z0-9])[A-Za-z]:[" + bs + "/]" + notSpace + "{2,}"
        val unc = bs + bs + notSpace + "+" + bs + notSpace + "+"
        val unix = "(?<![A-Za-z0-9])/(?:home|Users|etc|var|root)/" + notSpace + "*"
        Regex("(?:" + drive + "|" + unc + "|" + unix + ")")
    }

    /**
     * 关键词 + 赋值形态。分成两档：
     * · 强形态（= 或 :）后面接什么都算凭据；
     * · 弱形态（是 / 为）后面必须像令牌（拉丁字母/数字），
     *   否则「我不记得密码是什么了」这种正常中文会被误打码——上游也有这个误伤，这里收紧。
     */
    private val CREDENTIAL_STRONG: Regex = run {
        val words = "token|密码|密钥|口令|password|passwd|secret|api[_-]?key|authorization|bearer|access[_-]?key|credential"
        Regex(
            "(?:" + words + ")" + "[" + QUOTE + "]?" + "\\s*[:=：]" + "\\s*[" + QUOTE + "]?[^" + WS + "，。；、" + QUOTE + "]{3,}",
            RegexOption.IGNORE_CASE,
        )
    }

    /** 弱形态：只有像令牌的值才算。 */
    private val CREDENTIAL_WEAK: Regex = run {
        val words = "token|密码|密钥|口令|password|passwd|secret|api[_-]?key|authorization|bearer|access[_-]?key|credential"
        Regex(
            "(?:" + words + ")" + "[" + QUOTE + "]?" + "\\s*(?:是|为)" + "\\s*[" + QUOTE + "]?[A-Za-z0-9_\\-./+=]{3,}",
            RegexOption.IGNORE_CASE,
        )
    }

    data class Result(
        val text: String,
        val redacted: Boolean,
        val reasons: List<String>,
    ) {
        val clean: Boolean get() = !redacted
    }

    /**
     * 审查并打码。
     * @param knownSecrets 我们自己知道的密钥（当前配置的 API Key）。精确匹配，命中即替换。
     */
    fun inspect(text: String, knownSecrets: List<String> = emptyList()): Result {
        var out = text
        val reasons = mutableListOf<String>()

        // 1) 已知密钥：精确匹配优先。哪怕它长得不像任何模式，也必须拦住。
        for (secret in knownSecrets) {
            if (secret.isNotBlank() && secret.length >= 8 && out.contains(secret)) {
                out = out.replace(secret, "[已隐藏]")
                reasons += "命中了已知密钥"
            }
        }

        // 2) 本机路径。
        val pathHits = PATH_RE.findAll(out).map { it.value }.toList()
        if (pathHits.isNotEmpty()) {
            pathHits.forEach { out = out.replace(it, "[路径已隐藏]") }
            reasons += "出现了本机路径"
        }

        // 3) 带赋值形态的凭据（强形态与弱形态分开处理）。
        if (CREDENTIAL_STRONG.containsMatchIn(out)) {
            out = CREDENTIAL_STRONG.replace(out, "[凭据已隐藏]")
            reasons += "出现了凭据赋值"
        }
        if (CREDENTIAL_WEAK.containsMatchIn(out)) {
            out = CREDENTIAL_WEAK.replace(out, "[凭据已隐藏]")
            reasons += "出现了凭据赋值"
        }

        return Result(out, reasons.isNotEmpty(), reasons.distinct())
    }

    /**
     * 极保守的判断：这段内容是否完全不该发。
     * 只用于一种情况——整句话就是一条本机绝对路径（模型在复述内部状态，打码后没有意义）。
     * 其余一律「打码后照发」，不让审查变成静默丢弃。
     */
    fun shouldDropEntirely(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return PATH_RE.matchEntire(trimmed) != null
    }
}
