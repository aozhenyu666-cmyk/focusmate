package com.focusmate.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统 TTS。只报告真实回调：onStart/onDone/onError。
 * 「播完」不代表用户听到了，这里也不这么说。新的一句打断旧的一句，避免两段重叠。
 */
class Speaker(context: Context) {

    @Volatile var status: String = "未初始化"
        private set

    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code ->
        if (code == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            status = if (ready) "可用" else "系统没有中文语音包"
            if (ready) pending?.let { speak(it) }
        } else {
            status = "系统语音引擎不可用"
        }
        pending = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { status = "正在朗读" }
            override fun onDone(utteranceId: String?) { status = "朗读结束" }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { status = "朗读出错" }
            override fun onError(utteranceId: String?, errorCode: Int) { status = "朗读出错（$errorCode）" }
        })
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fm-" + System.currentTimeMillis())
    }

    fun stop() {
        tts.stop()
    }
}
