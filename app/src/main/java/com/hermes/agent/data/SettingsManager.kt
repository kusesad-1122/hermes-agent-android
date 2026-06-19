package com.hermes.agent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SettingsManager — 持久化应用设置。
 *
 * 分两层：
 * - 普通设置: SharedPreferences (theme, language, limits...)
 * - 敏感设置: EncryptedSharedPreferences (API keys — 复用 ProviderConfig 的 KeyStore)
 *
 * 所有设置通过 StateFlow 暴露，Compose UI 可直接观察。
 */
object SettingsManager {

    private const val PREFS_NAME = "hermes_settings"
    private const val SECURE_PREFS_NAME = "hermes_secure_settings"

    // Keys
    const val KEY_ACTIVE_PROVIDER = "active_provider_id"
    const val KEY_MODEL = "model"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_MAX_ITERATIONS = "max_iterations"
    const val KEY_AUTO_START = "auto_start_service"
    const val KEY_NOTIFY_ON_COMPLETE = "notify_on_complete"
    const val KEY_DARK_MODE = "dark_mode"
    const val KEY_FONT_SCALE = "font_scale"
    const val KEY_SYSTEM_PROMPT = "system_prompt"

    // Defaults
    private const val DEFAULT_TEMPERATURE = 0.7f
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val DEFAULT_MAX_ITERATIONS = 10
    private const val DEFAULT_FONT_SCALE = 1.0f

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences

    // StateFlows for reactive UI
    private val _activeProvider = MutableStateFlow("")
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _temperature = MutableStateFlow(DEFAULT_TEMPERATURE)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(DEFAULT_MAX_TOKENS)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _maxIterations = MutableStateFlow(DEFAULT_MAX_ITERATIONS)
    val maxIterations: StateFlow<Int> = _maxIterations.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _notifyOnComplete = MutableStateFlow(true)
    val notifyOnComplete: StateFlow<Boolean> = _notifyOnComplete.asStateFlow()

    private val _darkMode = MutableStateFlow(false)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _fontScale = MutableStateFlow(DEFAULT_FONT_SCALE)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private var initialized = false

    /**
     * 初始化，必须在 Application.onCreate 或 Activity.onCreate 中调用。
     */
    fun initialize(context: Context) {
        if (initialized) return

        // 普通设置
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 加密设置 (API Keys)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // 加载所有值到 StateFlows
        _activeProvider.value = prefs.getString(KEY_ACTIVE_PROVIDER, "") ?: ""
        _model.value = prefs.getString(KEY_MODEL, "") ?: ""
        _temperature.value = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        _maxTokens.value = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        _maxIterations.value = prefs.getInt(KEY_MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS)
        _autoStart.value = prefs.getBoolean(KEY_AUTO_START, false)
        _notifyOnComplete.value = prefs.getBoolean(KEY_NOTIFY_ON_COMPLETE, true)
        _darkMode.value = prefs.getBoolean(KEY_DARK_MODE, false)
        _fontScale.value = prefs.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)

        initialized = true
    }

    // ── Setters (save + update StateFlow) ──────────────────────────────────

    fun setActiveProvider(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, id).apply()
        _activeProvider.value = id
    }

    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
        _model.value = model
    }

    fun setTemperature(value: Float) {
        prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()
        _temperature.value = value
    }

    fun setMaxTokens(value: Int) {
        prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()
        _maxTokens.value = value
    }

    fun setMaxIterations(value: Int) {
        prefs.edit().putInt(KEY_MAX_ITERATIONS, value).apply()
        _maxIterations.value = value
    }

    fun setAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        _autoStart.value = enabled
    }

    fun setNotifyOnComplete(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_ON_COMPLETE, enabled).apply()
        _notifyOnComplete.value = enabled
    }

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        _darkMode.value = enabled
    }

    fun setFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale).apply()
        _fontScale.value = scale
    }

    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
    }

    fun getSystemPrompt(): String {
        return prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
    }

    // ── Secure storage (API Keys) ──────────────────────────────────────────

    fun setSecureString(key: String, value: String) {
        securePrefs.edit().putString(key, value).apply()
    }

    fun getSecureString(key: String): String? {
        return securePrefs.getString(key, null)
    }

    fun removeSecureString(key: String) {
        securePrefs.edit().remove(key).apply()
    }

    // ── Export all settings as map ─────────────────────────────────────────

    fun exportAll(): Map<String, Any?> {
        return mapOf(
            "active_provider" to _activeProvider.value,
            "model" to _model.value,
            "temperature" to _temperature.value,
            "max_tokens" to _maxTokens.value,
            "max_iterations" to _maxIterations.value,
            "auto_start" to _autoStart.value,
            "notify_on_complete" to _notifyOnComplete.value,
            "dark_mode" to _darkMode.value,
            "font_scale" to _fontScale.value,
            "system_prompt" to getSystemPrompt(),
        )
    }

    // ── Reset to defaults ─────────────────────────────────────────────────

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _activeProvider.value = ""
        _model.value = ""
        _temperature.value = DEFAULT_TEMPERATURE
        _maxTokens.value = DEFAULT_MAX_TOKENS
        _maxIterations.value = DEFAULT_MAX_ITERATIONS
        _autoStart.value = false
        _notifyOnComplete.value = true
        _darkMode.value = false
        _fontScale.value = DEFAULT_FONT_SCALE
    }
}