package com.salman.grammarai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    private val availableModels = listOf(
        ModelOption("Gemini 3.5 Flash Lite (500 req/day - Ultra Fast ⚡)", "gemini-3.5-flash-lite"),
        ModelOption("Gemini 3.1 Flash Lite (500 req/day - Fast ⚡)", "gemini-3.1-flash-lite"),
        ModelOption("Gemini 3.6 Flash (20 req/day)", "gemini-3.6-flash"),
        ModelOption("Gemini 3.8 Flash (20 req/day - High Reasoning)", "gemini-3.8-flash")
    )

    data class ModelOption(val displayName: String, val modelId: String) {
        override fun toString(): String = displayName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupApiKeySection()
        setupModelSection()
        setupPromptSection()
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

    private fun setupModelSection() {
        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            availableModels
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.spModel.adapter = adapter

        // Pre-select saved model
        val currentModelId = prefs.selectedModel
        val selectedIndex = availableModels.indexOfFirst { it.modelId == currentModelId }
        if (selectedIndex >= 0) {
            binding.spModel.setSelection(selectedIndex)
        }

        binding.spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = availableModels[position]
                if (prefs.selectedModel != selected.modelId) {
                    prefs.selectedModel = selected.modelId
                    Toast.makeText(this@MainActivity, "Model switched to: ${selected.modelId}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

    private fun setupPromptSection() {
        binding.etPrompt.setText(prefs.customPrompt)

        binding.btnSavePrompt.setOnClickListener {
            val prompt = binding.etPrompt.text?.toString()?.trim() ?: ""
            if (prompt.isBlank()) {
                binding.tilPrompt.error = "Prompt cannot be empty"
                return@setOnClickListener
            }
            binding.tilPrompt.error = null
            prefs.customPrompt = prompt
            Toast.makeText(this, "Prompt saved successfully!", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetPrompt.setOnClickListener {
            prefs.resetPromptToDefault()
            binding.etPrompt.setText(PreferencesManager.DEFAULT_PROMPT)
            binding.tilPrompt.error = null
            Toast.makeText(this, "Reset to default prompt!", Toast.LENGTH_SHORT).show()
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
            val result = geminiService.checkGrammar(text, prefs.apiKey, prefs.customPrompt, prefs.selectedModel)
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
