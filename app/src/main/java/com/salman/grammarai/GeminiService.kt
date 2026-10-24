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
    val explanation: String
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

                val root = JSONObject(body)
                val modelsArray = root.optJSONArray("models")
                    ?: return@withContext Result.failure(Exception("No models found in Google response"))

                val supportedList = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val name = m.getString("name")
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                    }
                    if (supportsGenerate) {
                        supportedList.add(name)
                    }
                }

                if (supportedList.isEmpty()) {
                    return@withContext Result.failure(Exception("No model supporting generateContent found for this key."))
                }

                // High-quota, ultra-fast Flash Lite models prioritized first!
                val priorityList = listOf(
                    "gemini-3.5-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-3.6-flash",
                    "gemini-3.8-flash"
                )

                var chosen: String? = null
                for (priority in priorityList) {
                    chosen = supportedList.firstOrNull { it.contains(priority, ignoreCase = true) }
                    if (chosen != null) break
                }

                if (chosen == null) {
                    chosen = supportedList.firstOrNull { it.contains("flash", ignoreCase = true) && !it.contains("2.5") && !it.contains("1.5") }
                        ?: supportedList.firstOrNull { it.contains("flash", ignoreCase = true) }
                        ?: supportedList.firstOrNull { it.contains("gemini", ignoreCase = true) }
                        ?: supportedList.first()
                }

                cachedDiscoveredModel = chosen
                Log.d(TAG, "Discovered active model: $chosen")
                Result.success(chosen)
            } catch (e: Exception) {
                Log.e(TAG, "Error discovering model", e)
                val friendlyError = if (e is SocketTimeoutException) {
                    Exception("Connection timed out while checking models.\nPlease check your VPN / Internet connection.")
                } else e
                Result.failure(friendlyError)
            }
        }
    }

    suspend fun checkGrammar(
        text: String,
        apiKey: String,
        promptTemplate: String = PreferencesManager.DEFAULT_PROMPT,
        preferredModel: String = PreferencesManager.DEFAULT_MODEL,
        isRetry: Boolean = false
    ): Result<GrammarResult> {
        return withContext(Dispatchers.IO) {
            try {
                val finalPrompt = if (promptTemplate.contains("{text}")) {
                    promptTemplate.replace("{text}", text)
                } else {
                    "$promptTemplate\n\nText:\n$text"
                }

                val partObj = JSONObject().put("text", finalPrompt)
                val partsArray = JSONArray().put(partObj)
                val contentObj = JSONObject().put("parts", partsArray)
                val contentsArray = JSONArray().put(contentObj)
                val requestJson = JSONObject().put("contents", contentsArray)

                // High-speed config: direct JSON output
                val generationConfig = JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", 800)
                    .put("responseMimeType", "application/json")

                requestJson.put("generationConfig", generationConfig)

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = requestJson.toString().toRequestBody(mediaType)

                val normalizedModel = preferredModel.removePrefix("models/")
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$normalizedModel:generateContent?key=$apiKey"

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
                    GrammarResult(
                        correctedText = resJson.optString("correctedText", text),
                        explanation = resJson.optString("explanation", "Grammar correction completed.")
                    )
                } catch (e: Exception) {
                    GrammarResult(
                        correctedText = rawText,
                        explanation = "Direct AI output."
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
