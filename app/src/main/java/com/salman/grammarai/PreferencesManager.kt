package com.salman.grammarai

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    val isApiKeyConfigured: Boolean
        get() = apiKey.isNotBlank()

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value.trim()).apply()

    var customPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROMPT, value.trim()).apply()

    var googleSheetsUrl: String
        get() = prefs.getString(KEY_GOOGLE_SHEETS_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_SHEETS_URL, value.trim()).apply()

    var ankiDeckName: String
        get() = prefs.getString(KEY_ANKI_DECK_NAME, "Dutch") ?: "Dutch"
        set(value) = prefs.edit().putString(KEY_ANKI_DECK_NAME, value.trim()).apply()

    var ankiEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANKI_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ANKI_ENABLED, value).apply()

    fun resetPromptToDefault() {
        prefs.edit().remove(KEY_CUSTOM_PROMPT).apply()
    }

    companion object {
        private const val PREFS_NAME = "grammar_ai_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
        private const val KEY_GOOGLE_SHEETS_URL = "google_sheets_url"
        private const val KEY_ANKI_DECK_NAME = "anki_deck_name"
        private const val KEY_ANKI_ENABLED = "anki_enabled"

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        val DEFAULT_PROMPT = """
You are an expert language and grammar tutor specializing in Dutch (Nederlands) and English.
The user has selected the following text:
\"\"\"
{text}
\"\"\"

Task:
1. Automatically detect whether the text is Dutch or English (or a mix).
2. Fix all grammatical mistakes, spelling errors, incorrect articles (de/het in Dutch), word order (Dutch V2 rule, inversion), verb conjugations, and phrasing to sound completely natural.
3. Provide concise explanation of the changes made and grammar rules behind them (in English), and include the fluent Persian translation under a header "🇮🇷 ترجمه فارسی".
4. Identify the primary key word, phrase, or expression that was misspelled, misused, or represents the main learning point.

Output your response strictly as a JSON object with this exact structure:
{
  "correctedText": "the corrected text only",
  "explanation": "concise bullet points explaining what was changed, ending with Persian translation under '🇮🇷 ترجمه فارسی'",
  "vocabulary_item": {
    "target_phrase": "key Dutch/English word or expression corrected/learned (e.g. het eerste)",
    "corrected_sentence": "the full corrected sentence containing that word/phrase",
    "phrase_meaning_fa": "Persian translation of this specific word/phrase",
    "sentence_meaning_fa": "Persian translation of the whole sentence"
  }
}
Do not add any text outside the JSON object.
        """.trimIndent()
    }
}
