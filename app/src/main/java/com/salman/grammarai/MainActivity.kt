package com.salman.grammarai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.salman.grammarai.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private val geminiService = GeminiService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupApiKeySection()
        setupTestSection()
    }

    private fun setupApiKeySection() {
        if (prefs.isApiKeyConfigured) {
            binding.etApiKey.setText(prefs.apiKey)
            verifyApiKey(prefs.apiKey)
        } else {
            binding.tvKeyStatus.text = "Status: No Key Set ⚠"
            binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
        }

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tilApiKey.error = "Key cannot be empty"
                return@setOnClickListener
            }
            binding.tilApiKey.error = null
            prefs.apiKey = key
            Toast.makeText(this, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
            verifyApiKey(key)
        }
    }

    private fun verifyApiKey(apiKey: String) {
        binding.tvKeyStatus.text = "Checking API Key with Google..."
        binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        lifecycleScope.launch {
            val discovery = geminiService.discoverBestModel(apiKey)
            discovery.onSuccess { modelName ->
                val cleanName = modelName.removePrefix("models/")
                binding.tvKeyStatus.text = "Status: Active ($cleanName) ✓"
                binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
            }.onFailure { error ->
                binding.tvKeyStatus.text = "Status: Error verifying key ⚠"
                binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                showErrorDialog("API Key Verification Failed", error.localizedMessage ?: "Unknown error")
            }
        }
    }

    private fun setupTestSection() {
        binding.btnTest.setOnClickListener {
            val text = binding.etTestText.text?.toString()?.trim() ?: ""
            if (text.isBlank()) {
                binding.tilTestText.error = "Please enter some text"
                return@setOnClickListener
            }
            binding.tilTestText.error = null

            if (!prefs.isApiKeyConfigured) {
                Toast.makeText(this, getString(R.string.api_key_missing), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            executeGrammarTest(text)
        }
    }

    private fun executeGrammarTest(text: String) {
        binding.pbLoading.visibility = View.VISIBLE
        binding.layoutTestResult.visibility = View.GONE
        binding.btnTest.isEnabled = false

        lifecycleScope.launch {
            val result = geminiService.checkGrammar(text, prefs.apiKey)
            binding.pbLoading.visibility = View.GONE
            binding.btnTest.isEnabled = true

            result.onSuccess { data ->
                binding.layoutTestResult.visibility = View.VISIBLE
                binding.tvTestCorrected.text = data.correctedText
                binding.tvTestExplanation.text = data.explanation
            }.onFailure { error ->
                showErrorDialog("Gemini API Error", error.localizedMessage ?: "Unknown error")
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy Error") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Error Details", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
