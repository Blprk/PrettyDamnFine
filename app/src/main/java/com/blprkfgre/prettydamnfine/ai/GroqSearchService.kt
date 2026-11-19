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
     * Scores every page by how often the query's meaningful terms occur, then
     * returns the most relevant pages first. When nothing matches, falls back to
     * an even sample across the document so broad questions still get context.
     */
    private fun rankPages(query: String, pageTexts: Map<Int, String>): List<Pair<Int, String>> {
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "of", "to", "in", "on",
            "for", "and", "or", "with", "this", "that", "these", "those", "it",
            "its", "as", "at", "by", "from", "be", "been", "do", "does", "did",
            "what", "when", "where", "which", "who", "how", "why", "can", "could",
            "would", "should", "about", "find", "show", "tell", "me", "please",
            "document", "pdf", "page"
        )
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()

        val scored = pageTexts.map { (page, text) ->
            val lower = text.lowercase()
            var score = 0
            for (term in terms) {
                var from = 0
                var count = 0
                while (count < 3) {
                    val idx = lower.indexOf(term, from)
                    if (idx < 0) break
                    count++
                    from = idx + term.length
                }
                score += count
            }
            page to score
        }

        val relevant = scored
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })

        if (relevant.isNotEmpty()) {
            return relevant.map { it.first to (pageTexts[it.first] ?: "") }
        }

        val sorted = pageTexts.entries.sortedBy { it.key }
        if (sorted.isEmpty()) return emptyList()
        val budget = 8
        val step = maxOf(1, sorted.size / budget)
        return sorted.filterIndexed { index, _ -> index % step == 0 }
            .take(budget)
            .map { it.key to it.value }
    }

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
            // Rank pages locally by query-term overlap so the model sees the parts of
            // the document that actually matter, not just the first few pages.
            val rankedPages = rankPages(query, pageTexts)
            val maxContextChars = 14000
            val maxCharsPerPage = 2400
            val contextText = buildString {
                for ((page, text) in rankedPages) {
                    if (length >= maxContextChars) break
                    if (text.isBlank()) continue
                    val remaining = maxOf(0, minOf(maxContextChars - length, maxCharsPerPage))
                    if (remaining < 40) break
                    append("--- Page ${page + 1} ---\n")
                    append(text.take(remaining).trim())
                    append("\n\n")
                }
            }

            val prompt = """
You answer questions using ONLY the numbered document excerpts below.

Document excerpts:
$contextText

Question: $query

Rules:
- Base your answer strictly on the excerpts. Do not use outside knowledge.
- If the answer is present, start with the exact format "Page X: " (X = the excerpt's page number) followed by a concise answer.
- If the answer is not present in any excerpt, reply exactly "Not found".
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
