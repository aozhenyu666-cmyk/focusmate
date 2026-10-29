package com.focusmate.companion

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音输出。一期用系统 TTS（执行指令允许"文字回复 + 系统 TTS"）。
 *
 * 三条明确边界：
 * 1. 同一时刻只允许一段语音：新的会先停掉旧的，避免两段叠在一起；
 * 2. TTS 完成 != 用户听到了（可能静音/戴耳机/被系统打断），所以只记"TTS 完成"；
 * 3. TTS 失败必须退回文字（文字已经展示了），并如实记下失败原因。
 */
class SpeechOutput(private val context: Context) {

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, message: String)
    }

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var pending: Pair<String, String>? = null
    private var currentUtteranceId: String? = null
    private var listener: Listener? = null

    var lastError: String? = null
        private set

    fun init(listener: Listener) {
        this.listener = listener
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { this@SpeechOutput.listener?.onStart(it) }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { this@SpeechOutput.listener?.onDone(it) }
                    }

                    @Deprecated("被 onError(String, Int) 取代，但仍需实现以兼容旧系统")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { this@SpeechOutput.listener?.onError(it, "未知 TTS 错误") }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let {
                            this@SpeechOutput.listener?.onError(it, "TTS 错误码 " + errorCode)
                        }
                    }
                })
                ready.set(true)
                pending?.let { (id, text) -> pending = null; speak(id, text) }
            } else {
                lastError = "TTS 初始化失败（status=" + status + "）"
                ready.set(false)
            }
        }
    }

    /** 说一句。返回 false 表示没送进 TTS，调用方应保留文字展示。 */
    fun speak(utteranceId: String, text: String): Boolean {
        val engine = tts
        if (engine == null || !ready.get()) {
            // 引擎还没好：最多挂起一句，不排队（排队会在恢复后突然连播一串旧内容）。
            pending = utteranceId to text
            lastError = "TTS 尚未就绪，已挂起一句"
            return false
        }
        // 新的语音先打断旧的：明确不做混播。
        currentUtteranceId?.let { engine.stop() }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        currentUtteranceId = utteranceId
        if (result != TextToSpeech.SUCCESS) {
            lastError = "TTS speak 返回错误码 " + result
            return false
        }
        lastError = null
        return true
    }

    fun stop() {
        tts?.stop()
        currentUtteranceId = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }
}
