package com.salman.grammarai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GrammarResult(
    val correctedText: String,
    val explanation: String
)

class GeminiService {

    companion object {
        private const val TAG = "GeminiService"
        @Volatile
        private var cachedModelName: String? = null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Dynamically finds the best available model for the given API key from Google.
     * Prefers fast flash models like gemini-3.8-flash, gemini-2.5-flash, gemini-2.0-flash, etc.
     */
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
                    val name = m.getString("name") // e.g., "models/gemini-3.8-flash"
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

                // Preference: flash models first (fastest for grammar), then any gemini model
                val chosen = supportedList.firstOrNull { it.contains("flash", ignoreCase = true) }
                    ?: supportedList.firstOrNull { it.contains("gemini", ignoreCase = true) }
                    ?: supportedList.first()

                cachedModelName = chosen
                Log.d(TAG, "Selected active model: $chosen (from ${supportedList.size} available models)")
                Result.success(chosen)
            } catch (e: Exception) {
                Log.e(TAG, "Error discovering model", e)
                Result.failure(e)
            }
        }
    }

    suspend fun checkGrammar(text: String, apiKey: String): Result<GrammarResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure we have an active model name discovered
                val modelToUse = cachedModelName ?: run {
                    val discovery = discoverBestModel(apiKey)
                    if (discovery.isFailure) {
                        return@withContext Result.failure(discovery.exceptionOrNull() ?: Exception("Failed to find available model"))
                    }
                    discovery.getOrThrow()
                }

                val prompt = """
                    You are an expert language and grammar tutor specializing in Dutch (Nederlands) and English.
                    The user has selected the following text:
                    \"\"\"
                    $text
                    \"\"\"

                    Task:
                    1. Automatically detect whether the text is Dutch or English (or a mix).
                    2. Fix all grammatical mistakes, spelling errors, incorrect articles (de/het in Dutch), word order (Dutch V2 rule, inversion), verb conjugations, and phrasing to sound completely natural.
                    3. Output your response strictly as a JSON object with exactly two string fields:
                    {
                      "correctedText": "the corrected text only",
                      "explanation": "brief bullet points explaining what was changed and the grammar rules behind it (in Dutch/English/Persian)"
                    }
                    Do not add any explanations outside the JSON object.
                """.trimIndent()

                val partObj = JSONObject().put("text", prompt)
                val partsArray = JSONArray().put(partObj)
                val contentObj = JSONObject().put("parts", partsArray)
                val contentsArray = JSONArray().put(contentObj)
                val requestJson = JSONObject().put("contents", contentsArray)

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = requestJson.toString().toRequestBody(mediaType)

                // modelToUse is in the format "models/gemini-3.8-flash"
                val normalizedModel = modelToUse.removePrefix("models/")
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$normalizedModel:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $responseBody"
                    }
                    // Invalidate cache if model somehow failed
                    cachedModelName = null
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
