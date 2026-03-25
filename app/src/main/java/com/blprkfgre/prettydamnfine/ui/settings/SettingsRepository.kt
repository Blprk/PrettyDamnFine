package com.blprkfgre.prettydamnfine.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_settings", Context.MODE_PRIVATE)
    
    // For API key - use encryption with fallback
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "pdf_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to normal shared prefs if encrypted one fails
            context.getSharedPreferences("pdf_secure_settings_fallback", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = encryptedPrefs.getString("groq_api_key", "") ?: ""
        set(value) = encryptedPrefs.edit().putString("groq_api_key", value).apply()

    var lastSearchQuery: String
        get() = prefs.getString("last_search_query", "") ?: ""
        set(value) = prefs.edit().putString("last_search_query", value).apply()

    var searchEnabled: Boolean
        get() = prefs.getBoolean("semantic_search_enabled", false)
        set(value) = prefs.edit().putBoolean("semantic_search_enabled", value).apply()

    var selectedModel: String
        get() = prefs.getString("selected_model", "llama-3.1-8b-instant") ?: "llama-3.1-8b-instant"
        set(value) = prefs.edit().putString("selected_model", value).apply()

    companion object {
        val AVAILABLE_MODELS = listOf(
            // Groq chat models — fast & free
            ModelOption("llama-3.1-8b-instant", "Llama 3.1 8B", "Fastest · Recommended"),
            ModelOption("llama-3.3-70b-versatile", "Llama 3.3 70B", "Most capable"),
            ModelOption("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B", "Efficient"),
            ModelOption("qwen/qwen3-32b", "Qwen 3 32B", "Powerful"),
            ModelOption("openai/gpt-oss-120b", "GPT OSS 120B", "Large model"),
            ModelOption("openai/gpt-oss-20b", "GPT OSS 20B", "Balanced"),
            ModelOption("moonshotai/kimi-k2-instruct", "Kimi K2", "General purpose"),
            ModelOption("groq/compound", "Groq Compound", "General purpose"),
            ModelOption("groq/compound-mini", "Groq Compound Mini", "Fast"),
            ModelOption("allam-2-7b", "Allam 2 7B", "General purpose"),
            // Random option
            ModelOption("random", "⚡ Random Model", "Try a random model")
        )
    }
}

data class ModelOption(val id: String, val name: String, val description: String)
