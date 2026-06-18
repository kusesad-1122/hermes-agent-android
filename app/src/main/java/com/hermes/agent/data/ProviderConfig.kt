package com.hermes.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 模型供应商配置
 */
data class ProviderConfig(
    val id: String,                    // 唯一标识 (uuid)
    val name: String,                  // 显示名称
    val type: ProviderType,            // 内置/自定义
    val baseUrl: String,               // API base URL
    val apiKeyRef: String,             // Keystore 中的 key 引用名
    val models: List<String> = emptyList(), // 已知模型列表
    val defaultModel: String = "",     // 默认模型
    val isActive: Boolean = true,      // 是否启用
    val lastTestOk: Boolean = false,   // 最后一次连通性测试是否通过
    val lastTestTime: Long = 0,        // 最后测试时间
    val createdAt: Long = System.currentTimeMillis()
)

enum class ProviderType {
    BUILTIN,    // 内置供应商 (OpenRouter, GLM, OpenAI 等)
    CUSTOM      // 自定义 OpenAI 兼容 endpoint
}

/**
 * 预置供应商模板
 */
object BuiltinProviders {
    val templates = listOf(
        ProviderTemplate(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            description = "聚合 200+ 模型，一个 key 覆盖大半市场",
            signupUrl = "https://openrouter.ai/"
        ),
        ProviderTemplate(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            description = "GPT-4o, GPT-4-turbo 等",
            signupUrl = "https://platform.openai.com/"
        ),
        ProviderTemplate(
            name = "GLM (智谱)",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            description = "GLM-5.2, GLM-4 等，1M 上下文",
            signupUrl = "https://open.bigmodel.cn/"
        ),
        ProviderTemplate(
            name = "Kimi (月之暗面)",
            baseUrl = "https://api.moonshot.cn/v1",
            description = "Kimi K2 等",
            signupUrl = "https://platform.moonshot.cn/"
        ),
        ProviderTemplate(
            name = "MiniMax",
            baseUrl = "https://api.minimax.chat/v1",
            description = "MiniMax-M1 等",
            signupUrl = "https://platform.minimaxi.com/"
        ),
        ProviderTemplate(
            name = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            description = "Grok-3 等",
            signupUrl = "https://console.x.ai/"
        ),
        ProviderTemplate(
            name = "Nous Portal",
            baseUrl = "https://api.nousresearch.com/v1",
            description = "Hermes 官方 Portal",
            signupUrl = "https://hermes-agent.nousresearch.com/"
        )
    )
}

data class ProviderTemplate(
    val name: String,
    val baseUrl: String,
    val description: String,
    val signupUrl: String
)

/**
 * 供应商存储 — API Key 使用 Android Keystore 加密
 */
class ProviderStorage(private val context: Context) {

    private val gson = Gson()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // 加密存储 API Key
    private val keyStore = EncryptedSharedPreferences.create(
        context,
        "hermes_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // 明文存储供应商配置（不含 key）
    private val prefs = context.getSharedPreferences("hermes_providers", Context.MODE_PRIVATE)

    fun saveProvider(config: ProviderConfig, apiKey: String) {
        // 存储配置
        val configs = getAllConfigs().toMutableList()
        val idx = configs.indexOfFirst { it.id == config.id }
        if (idx >= 0) configs[idx] = config else configs.add(config)
        prefs.edit().putString("providers", gson.toJson(configs)).apply()

        // 加密存储 API Key
        keyStore.edit().putString("key_${config.id}", apiKey).apply()
    }

    fun getApiKey(providerId: String): String {
        return keyStore.getString("key_$providerId", "") ?: ""
    }

    fun getAllConfigs(): List<ProviderConfig> {
        val json = prefs.getString("providers", "[]") ?: "[]"
        val type = object : TypeToken<List<ProviderConfig>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    fun getActiveConfigs(): List<ProviderConfig> {
        return getAllConfigs().filter { it.isActive }
    }

    fun deleteProvider(providerId: String) {
        val configs = getAllConfigs().filter { it.id != providerId }
        prefs.edit().putString("providers", gson.toJson(configs)).apply()
        keyStore.edit().remove("key_$providerId").apply()
    }

    fun updateTestResult(providerId: String, success: Boolean) {
        val configs = getAllConfigs().toMutableList()
        val idx = configs.indexOfFirst { it.id == providerId }
        if (idx >= 0) {
            configs[idx] = configs[idx].copy(lastTestOk = success, lastTestTime = System.currentTimeMillis())
            prefs.edit().putString("providers", gson.toJson(configs)).apply()
        }
    }

    fun getDefaultProvider(): ProviderConfig? {
        val active = getActiveConfigs()
        return active.firstOrNull { it.defaultModel.isNotEmpty() } ?: active.firstOrNull()
    }
}