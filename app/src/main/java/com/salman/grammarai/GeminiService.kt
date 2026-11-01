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
    val vocabularyItems: List<VocabularyItem> = emptyList()
) {
    val vocabularyItem: VocabularyItem?
        get() = vocabularyItems.firstOrNull()
}

data class TranslationResult(
    val originalText: String,
    val translationFa: String,
    val vocabularyItems: List<VocabularyItem> = emptyList()
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
        sentenceScope: Int = 0,
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

                val scopeDirective = when (sentenceScope) {
                    1 -> "The user provided the full text for context, but you MUST ONLY correct the LAST SINGLE SENTENCE. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from this last corrected sentence."
                    2 -> "The user provided the full text for context, but you MUST ONLY correct the LAST 2 SENTENCES. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from these last 2 corrected sentences."
                    3 -> "The user provided the full text for context, but you MUST ONLY correct the LAST 3 SENTENCES. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from these last 3 corrected sentences."
                    else -> "Correct all sentences in the provided text. Vocabulary items may be extracted from any part of the text."
                }

                var prompt = if (promptTemplate.contains("{scope_instruction}")) {
                    promptTemplate.replace("{scope_instruction}", scopeDirective)
                } else {
                    "$promptTemplate\n\nCorrection Scope Directive:\n$scopeDirective"
                }
                prompt = prompt.replace("{text}", text)

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
                        return@withContext checkGrammar(text, apiKey, promptTemplate, preferredModel, sentenceScope, isRetry = true)
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
                    return@withContext checkGrammar(text, apiKey, promptTemplate, preferredModel, sentenceScope, isRetry = true)
                }

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $responseBody"
                    }

                    // Fallback model if 404
                    if ((response.code == 404 || errorMsg.contains("not found", ignoreCase = true)) && normalizedModel != "gemini-3.1-flash-lite") {
                        Log.w(TAG, "Model $normalizedModel not found, falling back to gemini-3.1-flash-lite")
                        return@withContext checkGrammar(text, apiKey, promptTemplate, "gemini-3.1-flash-lite", sentenceScope, isRetry = true)
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

                    val vocabList = mutableListOf<VocabularyItem>()

                    // 1. Check array: "vocabulary_items"
                    val itemsArray = resJson.optJSONArray("vocabulary_items")
                        ?: resJson.optJSONArray("vocabularyItems")

                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val vObj = itemsArray.getJSONObject(i)
                            val item = parseVocabItem(vObj, corrected)
                            if (item != null && vocabList.none { it.targetPhrase.equals(item.targetPhrase, ignoreCase = true) }) {
                                vocabList.add(item)
                            }
                            if (vocabList.size >= 3) break
                        }
                    }

                    // 2. Fallback to single object: "vocabulary_item" if array empty
                    if (vocabList.isEmpty()) {
                        val singleObj = resJson.optJSONObject("vocabulary_item")
                            ?: resJson.optJSONObject("vocabularyItem")
                        if (singleObj != null) {
                            val item = parseVocabItem(singleObj, corrected)
                            if (item != null) {
                                vocabList.add(item)
                            }
                        }
                    }

                    GrammarResult(
                        correctedText = corrected,
                        explanation = explanation,
                        vocabularyItems = vocabList
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing Gemini JSON", e)
                    GrammarResult(
                        correctedText = rawText,
                        explanation = "Direct AI output.",
                        vocabularyItems = emptyList()
                    )
                }

                Result.success(parsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun parseVocabItem(json: JSONObject, fallbackSentence: String): VocabularyItem? {
        val targetPhrase = json.optString("target_phrase").ifBlank {
            json.optString("targetPhrase", "")
        }.trim()

        if (targetPhrase.isBlank()) return null

        val correctedSent = json.optString("corrected_sentence").ifBlank {
            json.optString("correctedSentence", fallbackSentence)
        }.trim()

        val phraseFa = json.optString("phrase_meaning_fa").ifBlank {
            json.optString("phraseMeaningFa", "")
        }.trim()

        val sentenceFa = json.optString("sentence_meaning_fa").ifBlank {
            json.optString("sentenceMeaningFa", "")
        }.trim()

        return VocabularyItem(
            targetPhrase = targetPhrase,
            correctedSentence = correctedSent,
            phraseMeaningFa = phraseFa,
            sentenceMeaningFa = sentenceFa
        )
    }

    suspend fun translateText(
        text: String,
        apiKey: String,
        preferredModel: String = PreferencesManager.DEFAULT_MODEL,
        isRetry: Boolean = false
    ): Result<TranslationResult> {
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

                val prompt = """
You are an expert translator specializing in Dutch (Nederlands), English, and Persian (Farsi).
The user copied the following message from a chat (e.g. WhatsApp):
\"\"\"
$text
\"\"\"

Task:
1. TRANSLATION: Translate the message into natural, fluent, and conversational Persian (Farsi).
2. KEY VOCABULARY: If the original text is in Dutch or English, extract 1 to 3 key words, idioms, or useful expressions for language learning.
   For each item provide:
   - "target_phrase": Base word or idiom in lemma/infinitive form.
   - "phrase_meaning_fa": Accurate Persian meaning of this phrase.
   - "corrected_sentence": A SINGLE, complete, standalone sentence in the original language containing this phrase.
   - "sentence_meaning_fa": Persian translation of this standalone sentence.

Output strictly valid JSON with this exact schema:
{
  "translation_fa": "ترجمه روان و طبیعی فارسی",
  "vocabulary_items": [
    {
      "target_phrase": "...",
      "phrase_meaning_fa": "...",
      "corrected_sentence": "...",
      "sentence_meaning_fa": "..."
    }
  ]
}
""".trimIndent()

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
                        Log.w(TAG, "Socket timeout in translation, retrying once...")
                        delay(1000)
                        return@withContext translateText(text, apiKey, preferredModel, isRetry = true)
                    }
                    return@withContext Result.failure(
                        Exception("Connection timed out.\n\nPlease ensure your VPN is connected and Grammar AI is not excluded in your VPN settings.")
                    )
                }

                val responseBody = response.body?.string() ?: ""

                if ((response.code == 503 || response.code == 429) && !isRetry) {
                    Log.w(TAG, "Server busy (HTTP ${response.code}). Retrying translation in 2s...")
                    delay(2000)
                    return@withContext translateText(text, apiKey, preferredModel, isRetry = true)
                }

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $responseBody"
                    }

                    if ((response.code == 404 || errorMsg.contains("not found", ignoreCase = true)) && normalizedModel != "gemini-3.1-flash-lite") {
                        Log.w(TAG, "Model $normalizedModel not found, falling back to gemini-3.1-flash-lite")
                        return@withContext translateText(text, apiKey, "gemini-3.1-flash-lite", isRetry = true)
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
                    val translation = resJson.optString("translation_fa").ifBlank {
                        resJson.optString("translation", "ترجمه انجام شد.")
                    }

                    val vocabList = mutableListOf<VocabularyItem>()
                    val itemsArray = resJson.optJSONArray("vocabulary_items")
                        ?: resJson.optJSONArray("vocabularyItems")

                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val vObj = itemsArray.getJSONObject(i)
                            val item = parseVocabItem(vObj, text)
                            if (item != null && vocabList.none { it.targetPhrase.equals(item.targetPhrase, ignoreCase = true) }) {
                                vocabList.add(item)
                            }
                            if (vocabList.size >= 3) break
                        }
                    }

                    TranslationResult(
                        originalText = text,
                        translationFa = translation,
                        vocabularyItems = vocabList
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing translation JSON", e)
                    TranslationResult(
                        originalText = text,
                        translationFa = rawText,
                        vocabularyItems = emptyList()
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
