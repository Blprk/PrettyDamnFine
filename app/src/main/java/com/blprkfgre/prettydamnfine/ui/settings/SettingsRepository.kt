package com.blprkfgre.prettydamnfine.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_settings", Context.MODE_PRIVATE)

    /**
     * Encrypted storage, with a one-shot recovery path for a stale/corrupt key.
     * A MAC failure means the stored file was encrypted with an old Android Keystore
     * key (common after reinstalls or restored data); discarding both lets us
     * re-encrypt with the current key instead of losing the setting entirely.
     */
    private val encryptedPrefs: SharedPreferences? by lazy {
        openEncryptedPrefs() ?: run {
            recoverKeystore()
            openEncryptedPrefs()
        }
    }

    private fun openEncryptedPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun recoverKeystore() {
        try {
            context.deleteSharedPreferences(SECURE_PREFS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** True when the API key can be saved across app restarts. */
    val isKeyPersistent: Boolean get() = encryptedPrefs != null

    var apiKey: String
        get() = encryptedPrefs?.getString(API_KEY, "")
            ?: sessionOnlyValues[API_KEY]
            ?: ""
        set(value) {
            val secure = encryptedPrefs
            if (secure != null) {
                secure.edit().putString(API_KEY, value).apply()
            } else {
                // Shared process-wide: the Settings screen and the PDF viewer create
                // separate repository instances and must see the same key.
                sessionOnlyValues[API_KEY] = value
            }
        }

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
        private const val SECURE_PREFS = "pdf_secure_settings"
        private const val API_KEY = "groq_api_key"

        private val sessionOnlyValues = mutableMapOf<String, String?>()

        val AVAILABLE_MODELS = listOf(
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
            ModelOption("random", "⚡ Random Model", "Try a random model")
        )
    }
}

data class ModelOption(val id: String, val name: String, val description: String)
