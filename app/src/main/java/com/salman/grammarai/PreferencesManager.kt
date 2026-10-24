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

    fun resetPromptToDefault() {
        prefs.edit().remove(KEY_CUSTOM_PROMPT).apply()
    }

    companion object {
        private const val PREFS_NAME = "grammar_ai_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"

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
3. Provide concise explanation of the changes made and grammar rules behind them (in English).

Output your response strictly as a JSON object with exactly two string fields:
{
  "correctedText": "the corrected text only",
  "explanation": "concise bullet points explaining what was changed, ending with Persian translation of the corrected sentence."
}
Do not add any explanations outside the JSON object.
        """.trimIndent()
    }
}
