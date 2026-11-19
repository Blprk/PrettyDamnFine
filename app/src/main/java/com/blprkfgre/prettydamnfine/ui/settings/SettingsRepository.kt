package com.blprkfgre.prettydamnfine.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
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
        get() = prefs.getString("selected_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("selected_model", value).apply()

    /** Live model IDs fetched from Groq; falls back to a current built-in list. */
    var availableModelIds: List<String>
        get() {
            val raw = prefs.getString("available_models", null) ?: return DEFAULT_MODEL_IDS
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
                    .filter { it.isNotBlank() }
                    .ifEmpty { DEFAULT_MODEL_IDS }
            } catch (e: Exception) {
                DEFAULT_MODEL_IDS
            }
        }
        set(value) {
            prefs.edit().putString("available_models", JSONArray(value).toString()).apply()
        }

    /** Model options for the UI, including any newly published Groq models. */
    fun modelOptions(): List<ModelOption> {
        val known = KNOWN_MODELS.associateBy { it.id }
        val options = availableModelIds.map { id ->
            known[id] ?: ModelOption(id, id.substringAfterLast("/"), "Available on Groq")
        }
        return (options + ModelOption("random", "⚡ Random Model", "Try a random model"))
            .distinctBy { it.id }
    }

    companion object {
        private const val SECURE_PREFS = "pdf_secure_settings"
        private const val API_KEY = "groq_api_key"

        const val DEFAULT_MODEL = "openai/gpt-oss-20b"

        private val sessionOnlyValues = mutableMapOf<String, String?>()

        // Active chat models as of Nov 2026. Retired Llama 3.1/3.3, Qwen 3 32B,
        // Llama 4 Scout and both Compound models are intentionally excluded.
        val DEFAULT_MODEL_IDS = listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b"
        )

        val AVAILABLE_MODELS = listOf(
            ModelOption("openai/gpt-oss-20b", "GPT OSS 20B", "Fastest · Recommended"),
            ModelOption("openai/gpt-oss-120b", "GPT OSS 120B", "Most capable"),
            ModelOption("qwen/qwen3.8-27b", "Qwen 3.8 27B", "Balanced"),
            ModelOption("random", "⚡ Random Model", "Try a random model")
        )

        private val KNOWN_MODELS = AVAILABLE_MODELS.filter { it.id != "random" }
    }
}

data class ModelOption(val id: String, val name: String, val description: String)
