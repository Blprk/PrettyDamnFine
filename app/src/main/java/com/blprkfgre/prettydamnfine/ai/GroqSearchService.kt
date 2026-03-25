package com.blprkfgre.prettydamnfine.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SemanticSearchResult(
    val pageNumber: Int,
    val content: String,
    val relevanceScore: Float = 0f
)

class GroqSearchService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Performs semantic search using Groq API
     */
    suspend fun semanticSearch(
        apiKey: String,
        query: String,
        pageTexts: Map<Int, String>,
        model: String = "llama-3.1-8b-instant"
    ): Result<List<SemanticSearchResult>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API key is required"))
        }

        if (query.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Search query is required"))
        }

        try {
            val allPages = pageTexts.entries.sortedBy { it.key }
            
            val maxContextChars = 8000
            val contextText = buildString {
                for ((page, text) in allPages) {
                    if (length >= maxContextChars) break
                    if (text.isBlank()) continue
                    append("--- Page ${page + 1} ---\n")
                    val remaining = maxContextChars - length
                    if (remaining <= 0) break
                    append(text.take(remaining).trim())
                    append("\n\n")
                }
            }

            val prompt = """
Search the document below and answer the question.

Document:
$contextText

Question: $query

If found, say "Page X: [answer]". If not found, say "Not found".
            """.trimIndent()

            val effectiveModel = if (model == "random") {
                listOf(
                    "llama-3.1-8b-instant",
                    "llama-3.3-70b-versatile",
                    "meta-llama/llama-4-scout-17b-16e-instruct",
                    "qwen/qwen3-32b",
                    "openai/gpt-oss-120b",
                    "openai/gpt-oss-20b",
                    "moonshotai/kimi-k2-instruct",
                    "groq/compound",
                    "groq/compound-mini",
                    "allam-2-7b"
                ).random()
            } else {
                model
            }

            val json = JSONObject().apply {
                put("model", effectiveModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.1)
                put("max_tokens", 1024)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.use { it.string() } ?: "Unknown error"
                val errorMsg = when (response.code) {
                    413 -> "Document too large. Try a shorter search."
                    429 -> "Rate limited. Wait and try again."
                    401 -> "Invalid API key. Check Settings."
                    402 -> "Need free credits. Get API key from groq.com"
                    404 -> "Model not found. Try a different model."
                    400 -> "Bad request. Try a different model."
                    else -> "Error ${response.code}"
                }
                return@withContext Result.failure(IOException("$errorMsg\n\n$errorBody"))
            }

            val responseBody = response.body?.use { it.string() } ?: ""
            val responseJson = JSONObject(responseBody)
            
            val choices = responseJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.success(emptyList())
            }

            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val pagePattern = Regex("page\\s*(\\d+)", setOf(RegexOption.IGNORE_CASE))
            val pageMatch = pagePattern.find(content)
            val pageNumber = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val result = SemanticSearchResult(
                pageNumber = pageNumber,
                content = content,
                relevanceScore = 1.0f
            )

            Result.success(listOf(result))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
