package com.focusmate.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.focusmate.agent.AgentPrefs
import com.focusmate.agent.Proactivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 伙伴设置与 API Key。
 * Key 用 Android Keystore 里的 AES 密钥加密后存放，不进日志、不进备份（allowBackup=false）、不进仓库。
 */
class AgentSettings(context: Context) {

    private val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("agent_secrets", Context.MODE_PRIVATE)

    fun load(): AgentPrefs {
        val d = AgentPrefs()
        return AgentPrefs(
            name = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: d.name,
            style = prefs.getString("style", null)?.takeIf { it.isNotBlank() } ?: d.style,
            proactivity = prefs.getString("proactivity", null)
                ?.let { runCatching { Proactivity.valueOf(it) }.getOrNull() } ?: d.proactivity,
            quietStartMinute = prefs.getInt("quietStart", d.quietStartMinute),
            quietEndMinute = prefs.getInt("quietEnd", d.quietEndMinute),
            speakAloud = prefs.getBoolean("speak", d.speakAloud),
            baseUrl = prefs.getString("baseUrl", null)?.takeIf { it.isNotBlank() } ?: d.baseUrl,
            model = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: d.model,
        )
    }

    fun save(p: AgentPrefs) {
        prefs.edit()
            .putString("name", p.name.trim())
            .putString("style", p.style.trim())
            .putString("proactivity", p.proactivity.name)
            .putInt("quietStart", p.quietStartMinute)
            .putInt("quietEnd", p.quietEndMinute)
            .putBoolean("speak", p.speakAloud)
            .putString("baseUrl", p.baseUrl.trim())
            .putString("model", p.model.trim())
            .apply()
    }

    /** 取 Key；没有或解密失败返回 null（解密失败通常是系统清了密钥，需要重新填）。 */
    fun apiKey(): String? {
        val blob = secrets.getString("apiKey", null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    fun hasApiKey(): Boolean = apiKey() != null

    /** 只显示末四位，界面用。 */
    fun apiKeyHint(): String? = apiKey()?.let { k -> "••••" + k.takeLast(4) }

    fun setApiKey(value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            secrets.edit().remove("apiKey").apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
        secrets.edit().putString("apiKey", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "focusmate_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
