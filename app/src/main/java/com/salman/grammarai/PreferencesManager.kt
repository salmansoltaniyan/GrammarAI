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

    /**
     * Correction scope:
     * 0 -> All sentences
     * 1 -> Last sentence only
     * 2 -> Last 2 sentences
     * 3 -> Last 3 sentences
     */
    var sentenceScope: Int
        get() = prefs.getInt(KEY_SENTENCE_SCOPE, 0)
        set(value) = prefs.edit().putInt(KEY_SENTENCE_SCOPE, value).apply()

    fun resetPromptToDefault() {
        prefs.edit().remove(KEY_CUSTOM_PROMPT).apply()
    }

    fun getScopeInstruction(scope: Int = sentenceScope): String {
        val boundaryRule = "Sentence boundary rule: A sentence is delimited by sentence-ending punctuation (., ?, !, or line break). Complex or compound clauses connected with conjunctions (e.g., 'omdat', 'want', 'maar', 'en', 'als', 'dus', 'hoewel', 'because', 'although', 'and', 'but') count as a SINGLE sentence unless separated by terminal punctuation."
        return when (scope) {
            1 -> "The user provided the full text for context, but you MUST ONLY correct the LAST SINGLE SENTENCE. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from this last corrected sentence. $boundaryRule"
            2 -> "The user provided the full text for context, but you MUST ONLY correct the LAST 2 SENTENCES. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from these last 2 corrected sentences. $boundaryRule"
            3 -> "The user provided the full text for context, but you MUST ONLY correct the LAST 3 SENTENCES. Keep all preceding sentences completely UNCHANGED word-for-word in 'correctedText'. Any extracted vocabulary items MUST come strictly from these last 3 corrected sentences. $boundaryRule"
            else -> "Correct all sentences in the provided text. Vocabulary items may be extracted from any part of the corrected text."
        }
    }

    fun buildEffectivePrompt(template: String, text: String, scope: Int = sentenceScope): String {
        val scopeInstruction = getScopeInstruction(scope)
        var prompt = if (template.contains("{scope_instruction}")) {
            template.replace("{scope_instruction}", scopeInstruction)
        } else {
            "$template\n\nCorrection Scope Directive:\n$scopeInstruction"
        }
        return prompt.replace("{text}", text)
    }

    companion object {
        private const val PREFS_NAME = "grammar_ai_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
        private const val KEY_GOOGLE_SHEETS_URL = "google_sheets_url"
        private const val KEY_ANKI_DECK_NAME = "anki_deck_name"
        private const val KEY_ANKI_ENABLED = "anki_enabled"
        private const val KEY_SENTENCE_SCOPE = "sentence_scope"

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        val DEFAULT_PROMPT = """
You are an expert language and grammar tutor specializing in Dutch (Nederlands), English, and Persian.
The user has provided the following text:
\"\"\"
{text}
\"\"\"

Task:
1. TARGET LANGUAGE INFERENCE (FROM THE FIRST WORD):
   - Inspect the FIRST word of the user's input:
     * If the first word is Dutch (e.g. 'Ik', 'We', 'Het', 'De', 'Waarom', 'Vandaag', 'Je', 'Als', 'Dus', 'Wat', etc.), the user intends the target language to be completely DUTCH! Even if the rest of the sentence is written in Persian (Farsi), English, or mixed languages, translate, adapt, and rewrite the whole message into natural, fluent Dutch!
     * If the first word is English, the target language is ENGLISH. Rewrite/translate the text into natural English.
     * If the first word is Persian, determine whether Dutch or English is expected based on context, and output fluent natural text in that language.
   - Never leave mixed languages in the output. The final text must be 100% in the intended target language.

2. CORRECTION SCOPE:
   {scope_instruction}

3. EXPLANATION:
   - Provide a concise explanation of changes and grammar rules in English.
   - Under the header "🇮🇷 ترجمه فارسی", provide the fluent Persian translation of the corrected sentence(s).

4. VOCABULARY SELECTION (Up to 3 items for Anki flashcards):
   - From the corrected sentence(s) (strictly respecting the correction scope above), extract up to 3 most important key words, phrases, expressions, or collocations to learn (especially items that were corrected, misspelled, or translated from Persian/English).
   - For each item:
     * "target_phrase": The specific word or phrase in the target language (e.g. "het eerste", "rekening houden met").
     * "corrected_sentence": A SINGLE, complete, standalone sentence containing this phrase. Do NOT include unrelated preceding sentences.
     * "phrase_meaning_fa": Accurate Persian translation of this specific word/phrase.
     * "sentence_meaning_fa": Accurate Persian translation of this single sentence.

Output your response strictly as a JSON object with this exact structure:
{
  "correctedText": "the full text with the requested scope corrected",
  "explanation": "concise bullet points explaining what was changed, ending with Persian translation under '🇮🇷 ترجمه فارسی'",
  "vocabulary_items": [
    {
      "target_phrase": "key Dutch/English word or phrase",
      "corrected_sentence": "single complete sentence containing the phrase",
      "phrase_meaning_fa": "Persian translation of this specific word/phrase",
      "sentence_meaning_fa": "Persian translation of the single sentence"
    }
  ]
}
Do not add any text outside the JSON object.
        """.trimIndent()
    }
}
