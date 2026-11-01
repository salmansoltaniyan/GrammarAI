package com.salman.grammarai

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.salman.grammarai.databinding.ItemVocabSelectionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class GrammarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GrammarAccessService"
        var isServiceRunning = false
            private set
        var instance: GrammarAccessibilityService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager
    private val geminiService = GeminiService()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var currentEditableNode: AccessibilityNodeInfo? = null

    // Floating Bubble views and params
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Floating Dialog views and params
    private var dialogView: View? = null
    private var dialogParams: WindowManager.LayoutParams? = null

    // Clipboard & Translation components
    private lateinit var clipboardManager: ClipboardManager

    // Translate Bubble views and params
    private var translateBubbleView: View? = null
    private var translateBubbleParams: WindowManager.LayoutParams? = null

    // Translation Dialog views and params
    private var translationDialogView: View? = null
    private var translationDialogParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        prefs = PreferencesManager(this)
        isServiceRunning = true

        if (prefs.clipboardTranslationEnabled && canDrawOverlays()) {
            showTranslateBubble()
        }

        Log.d(TAG, "GrammarAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (prefs.clipboardTranslationEnabled && canDrawOverlays()) {
            showTranslateBubble()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (instance == this) {
            instance = null
        }

        removeBubbleView()
        removeDialogView()
        removeTranslateBubbleView()
        removeTranslationDialogView()
        currentEditableNode?.recycle()
        currentEditableNode = null
        Log.d(TAG, "GrammarAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    currentEditableNode?.recycle()
                    currentEditableNode = AccessibilityNodeInfo.obtain(source)
                    showBubbleView()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun showBubbleView() {
        if (!canDrawOverlays()) return
        if (bubbleView != null) return // Already showing

        val themedContext = ContextThemeWrapper(this, R.style.Theme_GrammarAI)
        val inflater = LayoutInflater.from(themedContext)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 140
            y = resources.displayMetrics.heightPixels / 2
        }

        setupBubbleTouchListener()

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubble view", e)
            bubbleView = null
        }
    }

    private fun removeBubbleView() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing bubble view", e)
            }
        }
        bubbleView = null
    }

    private fun setupBubbleTouchListener() {
        val view = bubbleView ?: return
        val ivIcon = view.findViewById<ImageView>(R.id.ivBubbleIcon)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        ivIcon.setOnTouchListener { _, motionEvent ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (motionEvent.rawX - initialTouchX).toInt()
                    params.y = initialY + (motionEvent.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating bubble layout", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(motionEvent.rawX - initialTouchX)
                    val diffY = abs(motionEvent.rawY - initialTouchY)
                    // If moved less than 10 pixels, treat as a Click!
                    if (diffX < 10 && diffY < 10) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleClicked() {
        val node = currentEditableNode
        val text = node?.text?.toString()?.trim() ?: ""

        if (text.isBlank()) {
            Toast.makeText(this, "Please type or select some text first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!prefs.isApiKeyConfigured) {
            Toast.makeText(this, "Please set Gemini API Key in Grammar AI app", Toast.LENGTH_LONG).show()
            return
        }

        showFloatingDialog(text)
    }

    private fun showFloatingDialog(text: String) {
        if (!canDrawOverlays()) return
        removeDialogView()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_GrammarAI)
        val inflater = LayoutInflater.from(themedContext)
        dialogView = inflater.inflate(R.layout.layout_floating_dialog, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()

        dialogParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val root = dialogView ?: return
        val btnClose = root.findViewById<ImageButton>(R.id.btnFloatingClose)
        val layoutLoading = root.findViewById<LinearLayout>(R.id.floatingLoading)
        val layoutResult = root.findViewById<LinearLayout>(R.id.floatingResult)
        val tvError = root.findViewById<TextView>(R.id.tvFloatingError)
        val tvCorrected = root.findViewById<TextView>(R.id.tvFloatingCorrected)
        val tvExplanation = root.findViewById<TextView>(R.id.tvFloatingExplanation)
        val btnReplace = root.findViewById<MaterialButton>(R.id.btnFloatingReplace)
        val btnCopy = root.findViewById<MaterialButton>(R.id.btnFloatingCopy)
        val layoutVocab = root.findViewById<LinearLayout>(R.id.layoutFloatingVocab)
        val llVocabContainer = root.findViewById<LinearLayout>(R.id.llFloatingVocabContainer)
        val btnSaveVocab = root.findViewById<MaterialButton>(R.id.btnFloatingSaveVocab)

        btnClose.setOnClickListener {
            removeDialogView()
        }

        root.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_OUTSIDE) {
                removeDialogView()
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(root, dialogParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding dialog view", e)
            return
        }

        // Execute Grammar Check with Gemini
        serviceScope.launch {
            val result = geminiService.checkGrammar(
                text = text,
                apiKey = prefs.apiKey,
                promptTemplate = prefs.customPrompt,
                preferredModel = prefs.selectedModel,
                sentenceScope = prefs.sentenceScope
            )

            layoutLoading.visibility = View.GONE

            result.onSuccess { data ->
                layoutResult.visibility = View.VISIBLE
                tvCorrected.text = data.correctedText
                tvExplanation.text = data.explanation

                // Vocabulary selection (up to 3 items)
                val vocabItems = data.vocabularyItems
                llVocabContainer.removeAllViews()

                if (vocabItems.isNotEmpty()) {
                    layoutVocab.visibility = View.VISIBLE
                    val selectedItems = vocabItems.toMutableSet()

                    fun updateSaveButton() {
                        val count = selectedItems.size
                        btnSaveVocab.isEnabled = count > 0
                        btnSaveVocab.text = if (count > 0) {
                            "📥 Save Selected ($count) to Anki & Sheets"
                        } else {
                            "Select items above to save"
                        }
                    }

                    for (item in vocabItems) {
                        val itemBinding = ItemVocabSelectionBinding.inflate(inflater, llVocabContainer, false)
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

                        llVocabContainer.addView(itemBinding.root)
                    }

                    updateSaveButton()

                    btnSaveVocab.setOnClickListener {
                        val toSave = selectedItems.toList()
                        if (toSave.isEmpty()) return@setOnClickListener

                        btnSaveVocab.isEnabled = false
                        btnSaveVocab.text = "Saving ${toSave.size} item(s)..."

                        serviceScope.launch {
                            val res = VocabularyManager(this@GrammarAccessibilityService).saveItems(toSave)
                            val prefix = if (res.ankiSuccess || res.sheetsSuccess) "✓ " else "⚠️ "
                            btnSaveVocab.text = prefix + res.message
                            Toast.makeText(this@GrammarAccessibilityService, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    layoutVocab.visibility = View.GONE
                }

                btnCopy.setOnClickListener {
                    val clip = ClipData.newPlainText("Corrected Text", data.correctedText)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(this@GrammarAccessibilityService, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                    removeDialogView()
                }

                btnReplace.setOnClickListener {
                    replaceTextInFocusedNode(data.correctedText)
                    removeDialogView()
                }
            }.onFailure { error ->
                tvError.visibility = View.VISIBLE
                tvError.text = "Error: ${error.localizedMessage}"
            }
        }
    }

    private fun replaceTextInFocusedNode(newText: String) {
        val node = currentEditableNode ?: return
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (success) {
            Toast.makeText(this, "Text replaced successfully!", Toast.LENGTH_SHORT).show()
        } else {
            // Fallback: Copy to clipboard if host app disallowed direct replace
            val clip = ClipData.newPlainText("Corrected Text", newText)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(this, "Copied! (Paste into text field)", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeDialogView() {
        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dialog view", e)
            }
        }
        dialogView = null
    }

    // =========================================================================
    // Dedicated Translate Bubble (Option A: Tap 🌐 on screen to Translate)
    // =========================================================================

    fun showTranslateBubble() {
        if (!canDrawOverlays()) return
        if (translateBubbleView != null) return // Already showing

        val themedContext = ContextThemeWrapper(this, R.style.Theme_GrammarAI)
        val inflater = LayoutInflater.from(themedContext)
        translateBubbleView = inflater.inflate(R.layout.layout_floating_translate_bubble, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        translateBubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displayMetrics.widthPixels - 140
            y = displayMetrics.heightPixels / 3
        }

        setupTranslateBubbleTouchListener()

        try {
            windowManager.addView(translateBubbleView, translateBubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding translate bubble view", e)
            translateBubbleView = null
        }
    }

    fun removeTranslateBubbleView() {
        translateBubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing translate bubble view", e)
            }
        }
        translateBubbleView = null
    }

    private fun setupTranslateBubbleTouchListener() {
        val view = translateBubbleView ?: return
        val ivIcon = view.findViewById<ImageView>(R.id.ivTranslateBubbleIcon)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        ivIcon.setOnTouchListener { _, motionEvent ->
            val params = translateBubbleParams ?: return@setOnTouchListener false
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (motionEvent.rawX - initialTouchX).toInt()
                    params.y = initialY + (motionEvent.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating translate bubble layout", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(motionEvent.rawX - initialTouchX)
                    val diffY = abs(motionEvent.rawY - initialTouchY)
                    if (diffX < 15 && diffY < 15) {
                        onTranslateBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTranslateBubbleClicked() {
        if (!prefs.isApiKeyConfigured) {
            Toast.makeText(this, "Please set Gemini API Key in Grammar AI app", Toast.LENGTH_LONG).show()
            return
        }

        val clip = clipboardManager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
        } else {
            ""
        }

        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard is empty! Copy a message in WhatsApp first.", Toast.LENGTH_SHORT).show()
            return
        }

        showTranslationDialog(text)
    }

    private fun showTranslationDialog(initialText: String) {
        if (!canDrawOverlays()) return
        removeTranslationDialogView()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_GrammarAI)
        val inflater = LayoutInflater.from(themedContext)
        translationDialogView = inflater.inflate(R.layout.layout_dialog_translation, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()

        translationDialogParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val root = translationDialogView ?: return
        val btnClose = root.findViewById<ImageButton>(R.id.btnTranslateClose)
        val tvOriginal = root.findViewById<TextView>(R.id.tvTranslateOriginal)
        val btnPasteNew = root.findViewById<TextView>(R.id.btnPasteNewText)
        val layoutLoading = root.findViewById<LinearLayout>(R.id.translateLoading)
        val layoutResult = root.findViewById<LinearLayout>(R.id.translateResult)
        val tvError = root.findViewById<TextView>(R.id.tvTranslateError)
        val tvPersian = root.findViewById<TextView>(R.id.tvTranslatePersian)
        val btnCopy = root.findViewById<MaterialButton>(R.id.btnCopyTranslation)
        val layoutVocab = root.findViewById<LinearLayout>(R.id.layoutTranslateVocab)
        val llVocabContainer = root.findViewById<LinearLayout>(R.id.llTranslateVocabContainer)
        val btnSaveVocab = root.findViewById<MaterialButton>(R.id.btnTranslateSaveVocab)

        btnClose.setOnClickListener {
            removeTranslationDialogView()
        }

        root.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_OUTSIDE) {
                removeTranslationDialogView()
                true
            } else {
                false
            }
        }

        fun executeTranslation(textToTranslate: String) {
            tvOriginal.text = textToTranslate
            layoutLoading.visibility = View.VISIBLE
            layoutResult.visibility = View.GONE
            tvError.visibility = View.GONE

            serviceScope.launch {
                val result = geminiService.translateText(
                    text = textToTranslate,
                    apiKey = prefs.apiKey,
                    preferredModel = prefs.selectedModel
                )

                layoutLoading.visibility = View.GONE

                result.onSuccess { data ->
                    layoutResult.visibility = View.VISIBLE
                    tvPersian.text = data.translationFa

                    btnCopy.setOnClickListener {
                        val clip = ClipData.newPlainText("Persian Translation", data.translationFa)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(this@GrammarAccessibilityService, "Translation copied!", Toast.LENGTH_SHORT).show()
                    }

                    val vocabItems = data.vocabularyItems
                    llVocabContainer.removeAllViews()

                    if (vocabItems.isNotEmpty()) {
                        layoutVocab.visibility = View.VISIBLE
                        val selectedItems = vocabItems.toMutableSet()

                        fun updateSaveButton() {
                            val count = selectedItems.size
                            btnSaveVocab.isEnabled = count > 0
                            btnSaveVocab.text = if (count > 0) {
                                "📥 Save Selected ($count) to Anki & Sheets"
                            } else {
                                "Select items above to save"
                            }
                        }

                        for (item in vocabItems) {
                            val itemBinding = ItemVocabSelectionBinding.inflate(inflater, llVocabContainer, false)
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

                            llVocabContainer.addView(itemBinding.root)
                        }

                        updateSaveButton()

                        btnSaveVocab.setOnClickListener {
                            val toSave = selectedItems.toList()
                            if (toSave.isEmpty()) return@setOnClickListener

                            btnSaveVocab.isEnabled = false
                            btnSaveVocab.text = "Saving ${toSave.size} item(s)..."

                            serviceScope.launch {
                                val res = VocabularyManager(this@GrammarAccessibilityService).saveItems(toSave)
                                val prefix = if (res.ankiSuccess || res.sheetsSuccess) "✓ " else "⚠️ "
                                btnSaveVocab.text = prefix + res.message
                                Toast.makeText(this@GrammarAccessibilityService, res.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        layoutVocab.visibility = View.GONE
                    }
                }.onFailure { error ->
                    tvError.visibility = View.VISIBLE
                    tvError.text = "Error: ${error.localizedMessage}"
                }
            }
        }

        btnPasteNew.setOnClickListener {
            val clip = clipboardManager.primaryClip
            val newText = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } else ""

            if (newText.isBlank()) {
                Toast.makeText(this, "Clipboard is empty! Copy text first.", Toast.LENGTH_SHORT).show()
            } else {
                executeTranslation(newText)
            }
        }

        try {
            windowManager.addView(root, translationDialogParams)
            executeTranslation(initialText)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding translation dialog view", e)
        }
    }

    private fun removeTranslationDialogView() {
        translationDialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing translation dialog view", e)
            }
        }
        translationDialogView = null
    }
}
