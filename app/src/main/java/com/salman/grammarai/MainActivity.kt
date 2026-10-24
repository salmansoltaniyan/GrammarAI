package com.salman.grammarai

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
            updateKeyStatus(true)
        } else {
            updateKeyStatus(false)
        }

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tilApiKey.error = "Key cannot be empty"
                return@setOnClickListener
            }
            binding.tilApiKey.error = null
            prefs.apiKey = key
            updateKeyStatus(true)
            Toast.makeText(this, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateKeyStatus(hasKey: Boolean) {
        if (hasKey) {
            binding.tvKeyStatus.text = "Status: Key Active ✓"
            binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.tvKeyStatus.text = "Status: No Key Set ⚠"
            binding.tvKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
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
                Toast.makeText(this@MainActivity, "Error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

