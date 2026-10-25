package com.salman.grammarai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncResult(
    val ankiSuccess: Boolean,
    val sheetsSuccess: Boolean,
    val message: String
)

class VocabularyManager(private val context: Context) {

    private val ankiService = AnkiService()
    private val sheetsService = GoogleSheetsService()
    private val prefs = PreferencesManager(context)

    suspend fun saveItem(item: VocabularyItem): SyncResult {
        return withContext(Dispatchers.IO) {
            var ankiOk = false
            var sheetsOk = false
            val messages = mutableListOf<String>()

            // 1. Save to Anki
            if (prefs.ankiEnabled) {
                if (ankiService.isAnkiDroidInstalled(context) && ankiService.hasPermission(context)) {
                    val ankiRes = ankiService.addReversedFlashcard(
                        context = context,
                        item = item,
                        preferredDeckName = prefs.ankiDeckName
                    )
                    if (ankiRes.isSuccess) {
                        ankiOk = true
                        messages.add("✓ Anki (${prefs.ankiDeckName}: 2 cards)")
                    } else {
                        messages.add("✗ Anki error: ${ankiRes.exceptionOrNull()?.message}")
                    }
                } else if (!ankiService.isAnkiDroidInstalled(context)) {
                    messages.add("⚠️ AnkiDroid not installed")
                } else {
                    messages.add("⚠️ Anki permission needed")
                }
            }

            // 2. Save to Google Sheets
            val webhook = prefs.googleSheetsUrl.trim()
            if (webhook.isNotBlank()) {
                val sheetsRes = sheetsService.appendRow(webhook, item)
                if (sheetsRes.isSuccess) {
                    sheetsOk = true
                    messages.add("✓ Google Sheets")
                } else {
                    messages.add("✗ Sheets error")
                }
            }

            val finalMsg = if (ankiOk || sheetsOk) {
                "Saved: " + messages.joinToString(" | ")
            } else {
                "Not saved: " + messages.joinToString(" | ")
            }

            SyncResult(
                ankiSuccess = ankiOk,
                sheetsSuccess = sheetsOk,
                message = finalMsg
            )
        }
    }
}
