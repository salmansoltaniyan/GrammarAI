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
import com.salman.grammarai.databinding.ActivityProcessTextBinding
import kotlinx.coroutines.launch

class ProcessTextActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessTextBinding
    private lateinit var prefs: PreferencesManager
    private val geminiService = GeminiService()

    private var originalText: String = ""
    private var correctedText: String = ""
    private var isReadOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set dialog window width to match screen margins nicely
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        prefs = PreferencesManager(this)

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
            val result = geminiService.checkGrammar(originalText, prefs.apiKey, prefs.customPrompt)
            binding.layoutLoading.visibility = View.GONE

            result.onSuccess { data ->
                correctedText = data.correctedText
                binding.layoutResult.visibility = View.VISIBLE
                binding.tvCorrectedText.text = data.correctedText
                binding.tvExplanation.text = data.explanation
            }.onFailure { error ->
                showError("Gemini Error: ${error.localizedMessage}")
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

