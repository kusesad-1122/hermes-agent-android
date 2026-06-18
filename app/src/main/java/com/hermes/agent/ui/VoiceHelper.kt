package com.hermes.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * VoiceHelper — Android 原生语音输入/输出。
 *
 * STT: 使用 Android SpeechRecognizer（免费，离线可选，系统内置）
 * TTS: 使用 Android TextToSpeech（免费，系统内置）
 *
 * 用法：
 *   val voice = VoiceHelper(context)
 *   voice.startListening { text -> inputField = text }
 *   voice.speak("Hello world") { /* done */ }
 *   voice.shutdown()
 */
class VoiceHelper(private val context: Context) {

    companion object {
        private const val TAG = "VoiceHelper"
    }

    // ── STT ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── TTS ────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLanguage = Locale.CHINESE

    // ── State callbacks ────────────────────────────────────────────────────
    var onPartialResult: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ── Initialization ─────────────────────────────────────────────────────

    fun initialize(onReady: (() -> Unit)? = null) {
        // Init TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(ttsLanguage)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.US)
                    }
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f)
                    ttsReady = true
                    Log.d(TAG, "TTS initialized")
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
                onError?.invoke("TTS 初始化失败")
            }
            onReady?.invoke()
        }

        // Init STT (requires Activity context for permission dialog)
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d(TAG, "SpeechRecognizer available")
        } else {
            Log.w(TAG, "SpeechRecognizer not available on this device")
        }
    }

    // ── STT: Voice Input ───────────────────────────────────────────────────

    /**
     * Start voice recognition.
     *
     * @param languageCode BCP-47 language tag, e.g. "zh-CN", "en-US"
     * @param onResult Callback with final recognized text
     */
    fun startListening(
        languageCode: String = "zh-CN",
        onResult: (String) -> Unit
    ) {
        if (isListening) {
            stopListening()
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        onListeningStateChanged?.invoke(true)
                        Log.d(TAG, "Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech started")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                        onListeningStateChanged?.invoke(false)
                        Log.d(TAG, "Speech ended")
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        onListeningStateChanged?.invoke(false)
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎忙"
                            else -> "识别错误 ($error)"
                        }
                        Log.e(TAG, "STT error: $msg")
                        onError?.invoke(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "Final result: $text")
                        if (text.isNotEmpty()) {
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotEmpty()) {
                            onPartialResult?.invoke(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening ($languageCode)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onError?.invoke("启动语音识别失败: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Stop listening error", e)
        }
        isListening = false
        onListeningStateChanged?.invoke(false)
    }

    fun cancelListening() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Cancel listening error", e)
        }
        isListening = false
        onListeningStateChanged?.invoke(false)
    }

    fun isCurrentlyListening(): Boolean = isListening

    // ── TTS: Voice Output ──────────────────────────────────────────────────

    /**
     * Speak text aloud.
     *
     * @param text Text to speak
     * @param languageCode BCP-47 language tag for TTS engine
     * @param onComplete Callback when speaking finishes
     */
    fun speak(
        text: String,
        languageCode: String = "zh-CN",
        onComplete: (() -> Unit)? = null
    ) {
        if (!ttsReady || tts == null) {
            onError?.invoke("TTS 未就绪")
            onComplete?.invoke()
            return
        }

        // Set language
        val locale = Locale.forLanguageTag(languageCode)
        val langResult = tts?.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }

        // Set completion listener
        val utteranceId = "hermes_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done: $utteranceId")
                onComplete?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                onComplete?.invoke()
            }
        })

        // Speak
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        Log.d(TAG, "Speaking: ${text.take(50)}...")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    // ── TTS language support ───────────────────────────────────────────────

    fun getAvailableLanguages(): Set<Locale>? {
        return tts?.availableLanguages
    }

    fun setLanguage(locale: Locale) {
        ttsLanguage = locale
        tts?.setLanguage(locale)
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    fun shutdown() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "STT destroy error", e)
        }

        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        } catch (e: Exception) {
            Log.e(TAG, "TTS shutdown error", e)
        }

        isListening = false
    }
}