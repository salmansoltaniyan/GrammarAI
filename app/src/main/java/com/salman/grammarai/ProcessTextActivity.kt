package com.salman.grammarai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.salman.grammarai.databinding.ActivityProcessTextBinding
import com.salman.grammarai.databinding.ItemVocabSelectionBinding
import kotlinx.coroutines.launch

class ProcessTextActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessTextBinding
    private lateinit var prefs: PreferencesManager
    private val geminiService = GeminiService()
    private lateinit var vocabManager: VocabularyManager

    private var originalText: String = ""
    private var correctedText: String = ""
    private var isReadOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Beautiful centered dialog card
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        prefs = PreferencesManager(this)
        vocabManager = VocabularyManager(this)

        originalText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        binding.tvOriginalText.text = originalText

        setupListeners()

        if (!prefs.isApiKeyConfigured) {
            showError("Gemini API Key is not set.\nPlease open Grammar AI app to configure your key.")
            return
        }

        if (originalText.isBlank()) {
            showError("No text was selected.")
            return
        }

        processGrammarCheck()
    }

    private fun setupListeners() {
        binding.btnCloseDialog.setOnClickListener {
            finish()
        }

        binding.btnOpenSettings.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        binding.btnCopyResult.setOnClickListener {
            if (correctedText.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Corrected Text", correctedText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnReplaceResult.setOnClickListener {
            if (correctedText.isNotBlank()) {
                val returnIntent = Intent().apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, correctedText)
                }
                setResult(RESULT_OK, returnIntent)
                finish()
            }
        }

        if (isReadOnly) {
            binding.btnReplaceResult.visibility = View.GONE
        }
    }

    private fun processGrammarCheck() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        lifecycleScope.launch {
            val result = geminiService.checkGrammar(
                text = originalText,
                apiKey = prefs.apiKey,
                promptTemplate = prefs.customPrompt,
                preferredModel = prefs.selectedModel,
                sentenceScope = prefs.sentenceScope
            )
            binding.layoutLoading.visibility = View.GONE

            result.onSuccess { data ->
                correctedText = data.correctedText
                binding.layoutResult.visibility = View.VISIBLE
                binding.tvCorrectedText.text = data.correctedText
                binding.tvExplanation.text = data.explanation

                // Vocabulary selection (up to 3 items)
                setupVocabularySection(data.vocabularyItems)
            }.onFailure { error ->
                showError("Gemini Error: ${error.localizedMessage}")
            }
        }
    }

    private fun setupVocabularySection(items: List<VocabularyItem>) {
        val container = binding.llProcessVocabContainer
        container.removeAllViews()

        if (items.isEmpty()) {
            binding.layoutProcessVocab.visibility = View.GONE
            return
        }

        binding.layoutProcessVocab.visibility = View.VISIBLE
        val selectedItems = items.toMutableSet()

        fun updateSaveButton() {
            val count = selectedItems.size
            binding.btnProcessSaveVocab.isEnabled = count > 0
            binding.btnProcessSaveVocab.text = if (count > 0) {
                "📥 Save Selected ($count) to Anki & Sheets"
            } else {
                "Select items above to save"
            }
        }

        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val itemBinding = ItemVocabSelectionBinding.inflate(inflater, container, false)
            itemBinding.tvVocabTitle.text = "${item.targetPhrase} ➔ ${item.phraseMeaningFa}"
            itemBinding.tvVocabSentence.text = item.correctedSentence
            itemBinding.cbVocabSelect.isChecked = true

            itemBinding.cbVocabSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedItems.add(item)
                } else {
                    selectedItems.remove(item)
                }
                updateSaveButton()
            }

            container.addView(itemBinding.root)
        }

        updateSaveButton()

        binding.btnProcessSaveVocab.setOnClickListener {
            val toSave = selectedItems.toList()
            if (toSave.isEmpty()) return@setOnClickListener

            binding.btnProcessSaveVocab.isEnabled = false
            binding.btnProcessSaveVocab.text = "Saving ${toSave.size} item(s)..."

            lifecycleScope.launch {
                val res = vocabManager.saveItems(toSave)
                val prefix = if (res.ankiSuccess || res.sheetsSuccess) "✓ " else "⚠️ "
                binding.btnProcessSaveVocab.text = prefix + res.message
                Toast.makeText(this@ProcessTextActivity, res.message, Toast.LENGTH_LONG).show()

                if (!res.ankiSuccess && res.message.contains("Permission", ignoreCase = true)) {
                    val intent = Intent(this@ProcessTextActivity, MainActivity::class.java)
                    startActivity(intent)
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
    }
}
