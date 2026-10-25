package com.salman.grammarai

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi

class AnkiService {

    companion object {
        private const val TAG = "AnkiService"
        const val PERMISSION_ANKI = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    }

    fun isAnkiDroidInstalled(context: Context): Boolean {
        return AddContentApi.getAnkiDroidPackageName(context) != null
    }

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, PERMISSION_ANKI) == PackageManager.PERMISSION_GRANTED
    }

    fun getDeckList(context: Context): Map<Long, String> {
        if (!hasPermission(context)) return emptyMap()
        return try {
            val api = AddContentApi(context)
            api.deckList ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Anki decks", e)
            emptyMap()
        }
    }

    fun addReversedFlashcard(
        context: Context,
        item: VocabularyItem,
        preferredDeckName: String = "Dutch"
    ): Result<Long> {
        if (!isAnkiDroidInstalled(context)) {
            return Result.failure(Exception("AnkiDroid is not installed on this device."))
        }
        if (!hasPermission(context)) {
            return Result.failure(Exception("AnkiDroid database permission is not granted."))
        }

        return try {
            val api = AddContentApi(context)

            // 1. Resolve Target Deck
            val decks = api.deckList ?: emptyMap()
            var targetDeckId: Long? = null

            // Find existing deck by name (case-insensitive)
            for ((id, name) in decks) {
                if (name.equals(preferredDeckName, ignoreCase = true)) {
                    targetDeckId = id
                    break
                }
            }

            // If not found, create new deck with preferred name
            if (targetDeckId == null) {
                targetDeckId = api.addNewDeck(preferredDeckName.ifBlank { "GrammarAI" })
            }

            if (targetDeckId == null) {
                // Fallback to first available deck or default
                targetDeckId = decks.keys.firstOrNull() ?: 1L
            }

            // 2. Resolve or Create 2-Card Model ("Basic (and reversed card)")
            val models = api.modelList ?: emptyMap()
            var targetModelId: Long? = null

            // First check if our dedicated 2-way model already exists
            for ((id, name) in models) {
                if (name == "GrammarAI 2-Way") {
                    targetModelId = id
                    break
                }
            }

            // If not found, create our dedicated 2-way model
            if (targetModelId == null) {
                try {
                    targetModelId = api.addNewBasic2Model("GrammarAI 2-Way")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed creating GrammarAI 2-Way model", e)
                }
            }

            // Fallback: look for any model with reversed / 2-way
            if (targetModelId == null) {
                for ((id, name) in models) {
                    if (name.contains("reversed", ignoreCase = true) || name.contains("2-way", ignoreCase = true)) {
                        targetModelId = id
                        break
                    }
                }
            }

            // Fallback: create basic model or use first available
            if (targetModelId == null) {
                try {
                    targetModelId = api.addNewBasicModel("GrammarAI Basic")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed creating basic model", e)
                }
            }

            if (targetModelId == null) {
                targetModelId = models.keys.firstOrNull()
            }

            if (targetModelId == null) {
                return Result.failure(Exception("Unable to initialize note model in AnkiDroid."))
            }

            // 3. Format Card Content
            val front = """
                <div style="font-size: 20px; font-weight: bold; color: #1D4ED8; margin-bottom: 6px;">
                    ${item.targetPhrase}
                </div>
                <div style="font-size: 15px; color: #334155; line-height: 1.4;">
                    ${item.correctedSentence}
                </div>
            """.trimIndent()

            val back = """
                <div dir="rtl" style="font-size: 20px; font-weight: bold; color: #16A34A; margin-bottom: 6px;">
                    ${item.phraseMeaningFa}
                </div>
                <div dir="rtl" style="font-size: 15px; color: #334155; line-height: 1.4;">
                    ${item.sentenceMeaningFa}
                </div>
            """.trimIndent()

            // Dynamically match the exact number of fields expected by AnkiDroid
            val expectedFields = try {
                api.getFieldList(targetModelId) ?: emptyArray()
            } catch (e: Exception) {
                emptyArray()
            }
            val fieldCount = if (expectedFields.isNotEmpty()) expectedFields.size else 2

            val noteFields = Array(fieldCount) { i ->
                when (i) {
                    0 -> front
                    1 -> back
                    else -> ""
                }
            }

            val noteId = api.addNote(
                targetModelId,
                targetDeckId,
                noteFields,
                setOf("GrammarAI", "Dutch")
            )

            if (noteId != null && noteId > 0) {
                Log.i(TAG, "Successfully added 2-way note $noteId to deck $preferredDeckName")
                Result.success(noteId)
            } else {
                Result.failure(Exception("AnkiDroid rejected the new note."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding note to AnkiDroid", e)
            Result.failure(e)
        }
    }
}

