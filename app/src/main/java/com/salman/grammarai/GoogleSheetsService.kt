package com.salman.grammarai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GoogleSheetsService {

    companion object {
        private const val TAG = "GoogleSheetsService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun appendRow(webhookUrl: String, item: VocabularyItem): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (webhookUrl.isBlank()) {
                    return@withContext Result.failure(Exception("Google Sheets Webhook URL is empty."))
                }

                val payload = JSONObject().apply {
                    put("target_phrase", item.targetPhrase)
                    put("corrected_sentence", item.correctedSentence)
                    put("phrase_meaning_fa", item.phraseMeaningFa)
                    put("sentence_meaning_fa", item.sentenceMeaningFa)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "Google Sheets failed: ${response.code} $responseBody")
                    return@withContext Result.failure(Exception("HTTP ${response.code} from Google Sheets"))
                }

                Log.d(TAG, "Successfully appended to Google Sheets: $responseBody")
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error posting to Google Sheets", e)
                Result.failure(e)
            }
        }
    }
}
