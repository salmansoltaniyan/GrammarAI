package com.salman.grammarai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private val ankiService = AnkiService()

    private val availableModels = listOf(
        ModelOption("Gemini 3.5 Flash Lite (500 req/day - Ultra Fast ⚡)", "gemini-3.5-flash-lite"),
        ModelOption("Gemini 3.1 Flash Lite (500 req/day - Fast ⚡)", "gemini-3.1-flash-lite"),
        ModelOption("Gemini 3.6 Flash (20 req/day)", "gemini-3.6-flash"),
        ModelOption("Gemini 3.8 Flash (20 req/day - High Reasoning)", "gemini-3.8-flash")
    )

    data class ModelOption(val displayName: String, val modelId: String) {
        override fun toString(): String = displayName
    }

    private val ankiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateAnkiStatus()
        if (isGranted) {
            Toast.makeText(this, "AnkiDroid permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied. Anki cards cannot be saved.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupApiKeySection()
        setupModelSection()
        setupFloatingSection()
        setupAnkiSection()
        setupGoogleSheetsSection()
        setupPromptSection()
        setupTestSection()
    }

    override fun onResume() {
        super.onResume()
        updateFloatingStatus()
        updateAnkiStatus()
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

    private fun setupFloatingSection() {
        updateFloatingStatus()

        binding.btnConfigureFloating.setOnClickListener {
            // First check Overlay permission
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Display over other apps'", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            // Next check Accessibility permission
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Please enable 'Grammar AI' in Accessibility", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                return@setOnClickListener
            }

            Toast.makeText(this, "Floating Assistant is already active!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFloatingStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityServiceEnabled()

        if (hasOverlay && hasAccess) {
            binding.tvFloatingStatus.text = getString(R.string.status_floating_active)
            binding.tvFloatingStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnConfigureFloating.text = "Configured ✓"
        } else {
            val missing = when {
                !hasOverlay && !hasAccess -> "Overlay & Accessibility Needed"
                !hasOverlay -> "Overlay Permission Needed"
                else -> "Accessibility Service Needed"
            }
            binding.tvFloatingStatus.text = "Status: Inactive ($missing)"
            binding.tvFloatingStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.btnConfigureFloating.text = getString(R.string.btn_enable_floating)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, GrammarAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(expected)
    }

    private fun setupAnkiSection() {
        binding.etAnkiDeckName.setText(prefs.ankiDeckName)
        binding.btnSaveAnkiDeck.setOnClickListener {
            val deck = binding.etAnkiDeckName.text?.toString()?.trim() ?: "Dutch"
            prefs.ankiDeckName = deck
            Toast.makeText(this, "Target deck saved: $deck", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantAnkiPermission.setOnClickListener {
            ankiPermissionLauncher.launch(AnkiService.PERMISSION_ANKI)
        }

        binding.btnTestAnki.setOnClickListener {
            runAnkiDiagnostics()
        }

        updateAnkiStatus()
    }

    private fun updateAnkiStatus() {
        if (!ankiService.isAnkiDroidInstalled(this)) {
            binding.tvAnkiStatus.text = "Status: AnkiDroid Not Found ⚠️ (Please install AnkiDroid app)"
            binding.tvAnkiStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            binding.btnGrantAnkiPermission.visibility = View.GONE
        } else if (!ankiService.hasPermission(this)) {
            binding.tvAnkiStatus.text = "Status: Permission Needed ⚠️"
            binding.tvAnkiStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.btnGrantAnkiPermission.visibility = View.VISIBLE
        } else {
            binding.tvAnkiStatus.text = "Status: Connected to AnkiDroid ✓ (Target Deck: ${prefs.ankiDeckName})"
            binding.tvAnkiStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnGrantAnkiPermission.visibility = View.GONE
        }
    }

    private fun runAnkiDiagnostics() {
        binding.tvAnkiDiagnostic.visibility = View.VISIBLE
        val sb = StringBuilder()
        sb.append("=== AnkiDroid Diagnostic Report ===\n")

        // 1. Installation check
        val isInstalled = ankiService.isAnkiDroidInstalled(this)
        if (!isInstalled) {
            sb.append("❌ AnkiDroid app is NOT installed on this device.\nPlease install AnkiDroid from Google Play.")
            binding.tvAnkiDiagnostic.text = sb.toString()
            return
        }
        sb.append("✓ AnkiDroid package found\n")

        // 2. Permission check
        val hasPerm = ankiService.hasPermission(this)
        if (!hasPerm) {
            sb.append("❌ Permission NOT granted: Requesting permission now...\n")
            binding.tvAnkiDiagnostic.text = sb.toString()
            ankiPermissionLauncher.launch(AnkiService.PERMISSION_ANKI)
            return
        }
        sb.append("✓ Database permission granted\n")

        // 3. Decks query check
        val decks = ankiService.getDeckList(this)
        if (decks.isEmpty()) {
            sb.append("⚠️ Could not retrieve decks from AnkiDroid.\n")
            sb.append("👉 Important: Inside AnkiDroid app, go to:\nSettings -> Advanced -> Enable 'AnkiDroid API'\n")
            binding.tvAnkiDiagnostic.text = sb.toString()
            return
        }
        sb.append("✓ Found ${decks.size} deck(s): ${decks.values.take(5).joinToString(", ")}\n")

        // 4. Test note insertion
        val testDeck = prefs.ankiDeckName.ifBlank { "Dutch" }
        sb.append("Testing card creation in deck '$testDeck'...\n")

        val testItem = VocabularyItem(
            targetPhrase = "het begin (Test)",
            correctedSentence = "Dit is een testzin van Grammar AI.",
            phraseMeaningFa = "شروع / آغاز",
            sentenceMeaningFa = "این یک جمله آزمایشی از گرمر هوش مصنوعی است."
        )

        val res = ankiService.addReversedFlashcard(this, testItem, testDeck)
        if (res.isSuccess) {
            val noteId = res.getOrNull()
            sb.append("🎉 SUCCESS! 2-Way Flashcard added!\nNote ID: $noteId in deck '$testDeck'\nOpen AnkiDroid to review your new cards.")
        } else {
            sb.append("❌ Failed to add note: ${res.exceptionOrNull()?.localizedMessage}\n")
            sb.append("👉 Check that 'AnkiDroid API' is enabled in AnkiDroid -> Settings -> Advanced.")
        }

        binding.tvAnkiDiagnostic.text = sb.toString()
    }

    private fun setupGoogleSheetsSection() {
        binding.etGoogleSheetsUrl.setText(prefs.googleSheetsUrl)
        binding.btnSaveGoogleSheets.setOnClickListener {
            val url = binding.etGoogleSheetsUrl.text?.toString()?.trim() ?: ""
            prefs.googleSheetsUrl = url
            Toast.makeText(this, "Google Sheets Webhook URL saved!", Toast.LENGTH_SHORT).show()
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
                var fullExplanation = data.explanation
                if (data.vocabularyItem != null) {
                    fullExplanation += "\n\n💡 Extracted Vocabulary:\n" +
                            "• Target Phrase: ${data.vocabularyItem.targetPhrase}\n" +
                            "• Meaning (FA): ${data.vocabularyItem.phraseMeaningFa}\n" +
                            "• Sentence (FA): ${data.vocabularyItem.sentenceMeaningFa}"
                }
                binding.tvTestExplanation.text = fullExplanation
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
