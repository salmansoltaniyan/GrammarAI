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

    companion object {
        private const val PREFS_NAME = "grammar_ai_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
    }
}
