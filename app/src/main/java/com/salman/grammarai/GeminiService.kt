package com.salman.grammarai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class GrammarResult(
    val correctedText: String,
    val explanation: String,
    val vocabularyItem: VocabularyItem? = null
)

class GeminiService {

    companion object {
        private const val TAG = "GeminiService"
        @Volatile
        private var cachedDiscoveredModel: String? = null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun discoverBestModel(apiKey: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: throw IOException("Empty response from Google API")

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(body).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val modelsJson = JSONObject(body).getJSONArray("models")
                val availableModels = mutableListOf<String>()
                for (i in 0 until modelsJson.length()) {
                    val m = modelsJson.getJSONObject(i)
                    val name = m.getString("name").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") canGenerate = true
                    }
                    if (canGenerate) {
                        availableModels.add(name)
                    }
                }

                Log.d(TAG, "Available models on user key: $availableModels")

                // Preferred priority list (prioritizing 3.5 Flash Lite with 500 req/day)
                val priorityList = listOf(
                    "gemini-3.5-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-2.0-flash",
                    "gemini-2.0-flash-lite",
                    "gemini-1.5-flash"
                )

                val selected = priorityList.firstOrNull { it in availableModels }
                    ?: availableModels.firstOrNull { it.contains("flash", ignoreCase = true) }
                    ?: availableModels.firstOrNull()
                    ?: PreferencesManager.DEFAULT_MODEL

                cachedDiscoveredModel = selected
                Log.i(TAG, "Selected best model: $selected")
                Result.success(selected)
            } catch (e: Exception) {
                Log.e(TAG, "Error discovering models: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun checkGrammar(
        text: String,
        apiKey: String,
        promptTemplate: String,
        preferredModel: String = PreferencesManager.DEFAULT_MODEL,
        isRetry: Boolean = false
    ): Result<GrammarResult> {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(
                        Exception("Gemini API key is missing. Please set it in Settings.")
                    )
                }

                val normalizedModel = when {
                    preferredModel.isNotBlank() -> preferredModel
                    cachedDiscoveredModel != null -> cachedDiscoveredModel!!
                    else -> PreferencesManager.DEFAULT_MODEL
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$normalizedModel:generateContent?key=$apiKey"

                val prompt = promptTemplate.replace("{text}", text)

                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.2)
                        put("responseMimeType", "application/json")
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingLevel", "low")
                        })
                    })
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = try {
                    client.newCall(request).execute()
                } catch (e: SocketTimeoutException) {
                    if (!isRetry) {
                        Log.w(TAG, "Socket timeout, retrying once...")
                        delay(1000)
                        return@withContext checkGrammar(text, apiKey, promptTemplate, preferredModel, isRetry = true)
                    }
                    return@withContext Result.failure(
                        Exception("Connection timed out.\n\nPlease ensure your VPN is connected and Grammar AI is not excluded in your VPN settings.")
                    )
                }

                val responseBody = response.body?.string() ?: ""

                // Handle 503 (Server Busy) or 429 (Rate Limit) with automatic backoff retry
                if ((response.code == 503 || response.code == 429) && !isRetry) {
                    Log.w(TAG, "Server busy (HTTP ${response.code}). Retrying in 2s...")
                    delay(2000)
                    return@withContext checkGrammar(text, apiKey, promptTemplate, preferredModel, isRetry = true)
                }

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $responseBody"
                    }

                    // If model is not available or rate limited, fallback to gemini-3.1-flash-lite
                    if ((response.code == 404 || errorMsg.contains("not found", ignoreCase = true)) && normalizedModel != "gemini-3.1-flash-lite") {
                        Log.w(TAG, "Model $normalizedModel not found, falling back to gemini-3.1-flash-lite")
                        return@withContext checkGrammar(text, apiKey, promptTemplate, "gemini-3.1-flash-lite", isRetry = true)
                    }

                    return@withContext Result.failure(Exception("[$normalizedModel] $errorMsg"))
                }

                val rootJson = JSONObject(responseBody)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext Result.failure(Exception("No candidate response generated by model $normalizedModel"))
                }

                val content = candidates.getJSONObject(0).getJSONObject("content")
                val responseParts = content.getJSONArray("parts")
                val rawText = responseParts.getJSONObject(0).getString("text").trim()

                val cleanedJson = cleanJsonOutput(rawText)
                val parsed = try {
                    val resJson = JSONObject(cleanedJson)
                    val corrected = resJson.optString("correctedText", text).ifBlank {
                        resJson.optString("corrected_text", text)
                    }
                    val explanation = resJson.optString("explanation", "Grammar check completed.")

                    var vocabItem: VocabularyItem? = null
                    val vocabJson = resJson.optJSONObject("vocabulary_item") ?: resJson.optJSONObject("vocabularyItem")
                    if (vocabJson != null) {
                        val targetPhrase = vocabJson.optString("target_phrase").ifBlank {
                            vocabJson.optString("targetPhrase", "")
                        }
                        val correctedSent = vocabJson.optString("corrected_sentence").ifBlank {
                            vocabJson.optString("correctedSentence", corrected)
                        }
                        val phraseFa = vocabJson.optString("phrase_meaning_fa").ifBlank {
                            vocabJson.optString("phraseMeaningFa", "")
                        }
                        val sentenceFa = vocabJson.optString("sentence_meaning_fa").ifBlank {
                            vocabJson.optString("sentenceMeaningFa", "")
                        }

                        if (targetPhrase.isNotBlank()) {
                            vocabItem = VocabularyItem(
                                targetPhrase = targetPhrase,
                                correctedSentence = correctedSent,
                                phraseMeaningFa = phraseFa,
                                sentenceMeaningFa = sentenceFa
                            )
                        }
                    }

                    GrammarResult(
                        correctedText = corrected,
                        explanation = explanation,
                        vocabularyItem = vocabItem
                    )
                } catch (e: Exception) {
                    GrammarResult(
                        correctedText = rawText,
                        explanation = "Direct AI output.",
                        vocabularyItem = null
                    )
                }

                Result.success(parsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun cleanJsonOutput(raw: String): String {
        var result = raw.trim()
        if (result.startsWith("```json")) {
            result = result.removePrefix("```json")
        } else if (result.startsWith("```")) {
            result = result.removePrefix("```")
        }
        if (result.endsWith("```")) {
            result = result.removeSuffix("```")
        }
        return result.trim()
    }
}
