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
                        messages.add("✗ Anki: ${ankiRes.exceptionOrNull()?.message}")
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

    suspend fun saveItems(items: List<VocabularyItem>): SyncResult {
        return withContext(Dispatchers.IO) {
            if (items.isEmpty()) {
                return@withContext SyncResult(false, false, "No vocabulary items selected")
            }

            var totalAnkiSaved = 0
            var totalSheetsSaved = 0
            val errors = mutableListOf<String>()

            for (item in items) {
                val res = saveItem(item)
                if (res.ankiSuccess) totalAnkiSaved++
                if (res.sheetsSuccess) totalSheetsSaved++
                if (!res.ankiSuccess && !res.sheetsSuccess) {
                    errors.add(res.message)
                }
            }

            val summaryParts = mutableListOf<String>()
            if (totalAnkiSaved > 0) {
                summaryParts.add("✓ Anki ($totalAnkiSaved items)")
            }
            if (totalSheetsSaved > 0) {
                summaryParts.add("✓ Sheets ($totalSheetsSaved items)")
            }
            if (summaryParts.isEmpty() && errors.isNotEmpty()) {
                summaryParts.add(errors.first())
            }

            val message = if (totalAnkiSaved > 0 || totalSheetsSaved > 0) {
                "Saved: " + summaryParts.joinToString(" | ")
            } else {
                summaryParts.joinToString(" | ")
            }

            SyncResult(
                ankiSuccess = totalAnkiSaved > 0,
                sheetsSuccess = totalSheetsSaved > 0,
                message = message
            )
        }
    }
}
