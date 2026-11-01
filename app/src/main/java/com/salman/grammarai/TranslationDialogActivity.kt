package com.salman.grammarai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.salman.grammarai.databinding.ItemVocabSelectionBinding
import com.salman.grammarai.databinding.LayoutDialogTranslationBinding
import kotlinx.coroutines.launch

class TranslationDialogActivity : AppCompatActivity() {

    private lateinit var binding: LayoutDialogTranslationBinding
    private lateinit var prefs: PreferencesManager
    private val geminiService = GeminiService()
    private lateinit var vocabManager: VocabularyManager
    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutDialogTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Center dialog sizing
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        prefs = PreferencesManager(this)
        vocabManager = VocabularyManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        setupListeners()

        // 1. Check if launched via Process Text intent
        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (!processText.isNullOrBlank()) {
            startTranslation(processText)
        } else {
            // 2. Read from clipboard (100% permitted in Activity foreground)
            val clipText = readClipboardText()
            if (clipText.isNotBlank()) {
                startTranslation(clipText)
            } else {
                showEmptyClipboardState()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        if (!processText.isNullOrBlank()) {
            startTranslation(processText)
        } else {
            val clipText = readClipboardText()
            if (clipText.isNotBlank()) {
                startTranslation(clipText)
            }
        }
    }

    private fun readClipboardText(): String {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun setupListeners() {
        binding.btnTranslateClose.setOnClickListener {
            finish()
        }

        binding.btnPasteNewText.setOnClickListener {
            val newText = readClipboardText()
            if (newText.isBlank()) {
                Toast.makeText(this, "Clipboard is empty! Copy text in WhatsApp first.", Toast.LENGTH_SHORT).show()
            } else {
                startTranslation(newText)
            }
        }
    }

    private fun showEmptyClipboardState() {
        binding.tvTranslateOriginal.text = "No text found in clipboard.\n\n👉 Copy a message in WhatsApp, then tap '📋 Paste New' above or tap the 🌐 bubble!"
        binding.translateLoading.visibility = View.GONE
        binding.translateResult.visibility = View.GONE
        binding.tvTranslateError.visibility = View.GONE
    }

    private fun startTranslation(text: String) {
        if (!prefs.isApiKeyConfigured) {
            binding.tvTranslateError.visibility = View.VISIBLE
            binding.tvTranslateError.text = "Gemini API Key is not set. Please open Grammar AI app to configure your key."
            binding.translateLoading.visibility = View.GONE
            binding.translateResult.visibility = View.GONE
            return
        }

        binding.tvTranslateOriginal.text = text
        binding.translateLoading.visibility = View.VISIBLE
        binding.translateResult.visibility = View.GONE
        binding.tvTranslateError.visibility = View.GONE

        lifecycleScope.launch {
            val result = geminiService.translateText(
                text = text,
                apiKey = prefs.apiKey,
                preferredModel = prefs.selectedModel
            )

            binding.translateLoading.visibility = View.GONE

            result.onSuccess { data ->
                binding.translateResult.visibility = View.VISIBLE
                binding.tvTranslatePersian.text = data.translationFa

                binding.btnCopyTranslation.setOnClickListener {
                    val clip = ClipData.newPlainText("Persian Translation", data.translationFa)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(this@TranslationDialogActivity, "Translation copied!", Toast.LENGTH_SHORT).show()
                }

                val vocabItems = data.vocabularyItems
                binding.llTranslateVocabContainer.removeAllViews()

                if (vocabItems.isNotEmpty()) {
                    binding.layoutTranslateVocab.visibility = View.VISIBLE
                    val selectedItems = vocabItems.toMutableSet()

                    fun updateSaveButton() {
                        val count = selectedItems.size
                        binding.btnTranslateSaveVocab.isEnabled = count > 0
                        binding.btnTranslateSaveVocab.text = if (count > 0) {
                            "📥 Save Selected ($count) to Anki & Sheets"
                        } else {
                            "Select items above to save"
                        }
                    }

                    for (item in vocabItems) {
                        val itemBinding = ItemVocabSelectionBinding.inflate(layoutInflater, binding.llTranslateVocabContainer, false)
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

                        binding.llTranslateVocabContainer.addView(itemBinding.root)
                    }

                    updateSaveButton()

                    binding.btnTranslateSaveVocab.setOnClickListener {
                        val toSave = selectedItems.toList()
                        if (toSave.isEmpty()) return@setOnClickListener

                        binding.btnTranslateSaveVocab.isEnabled = false
                        binding.btnTranslateSaveVocab.text = "Saving ${toSave.size} item(s)..."

                        lifecycleScope.launch {
                            val res = vocabManager.saveItems(toSave)
                            val prefix = if (res.ankiSuccess || res.sheetsSuccess) "✓ " else "⚠️ "
                            binding.btnTranslateSaveVocab.text = prefix + res.message
                            Toast.makeText(this@TranslationDialogActivity, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    binding.layoutTranslateVocab.visibility = View.GONE
                }
            }.onFailure { error ->
                binding.tvTranslateError.visibility = View.VISIBLE
                binding.tvTranslateError.text = "Error: ${error.localizedMessage}"
            }
        }
    }
}
