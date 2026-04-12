package com.omnix.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R
import com.omnix.agent.ai.GemmaInferenceEngine
import android.util.Log
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.ChatMessageEntity
import com.omnix.agent.database.ChatSessionEntity
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.executor.TermuxBridge
import com.omnix.agent.skills.SkillLibraryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import kotlinx.coroutines.flow.catch

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val SPEECH_REQUEST = 42
        private const val TAG = "ChatActivity"
    }

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var tvThinking: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var adapter: ChatAdapter

    // ── Session persistence ───────────────────────────────────────────────────
    private val sessionId = UUID.randomUUID().toString()
    private var sessionStarted = false
    private var firstUserMessage = ""
    private var a11yWarningShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        OmnixOrchestrator.initialize(this)
        SkillLibraryManager.initialize(applicationContext)

        rvMessages  = findViewById(R.id.rv_messages)
        etInput     = findViewById(R.id.et_input)
        btnSend     = findViewById(R.id.btn_send)
        btnMic      = findViewById(R.id.btn_mic)
        tvThinking  = findViewById(R.id.tv_thinking)
        tvAiStatus  = findViewById(R.id.tv_ai_status)

        adapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = adapter

        // Back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Clear chat
        findViewById<ImageButton>(R.id.btn_clear).setOnClickListener {
            adapter.clear()
            GemmaInferenceEngine.clearChatHistory()
            addAiMessage("Chat cleared. How can I help you?")
        }

        // History button — opens ChatHistoryActivity
        val btnHistory = findViewById<ImageButton?>(R.id.btn_history)
        btnHistory?.setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        setupInputActions()
        showFullStatus()

        addAiMessage("Hi! I'm OMNIX — your autonomous AI assistant. I can control any app, run code, write scripts, and learn from each task.\n\nJust tell me what to do!")

        // Handle test_message from ADB intent (cold start) — wait for Gemma to init
        intent?.getStringExtra("test_message")?.let { testText ->
            if (testText.isNotBlank()) {
                Log.i(TAG, "Test message on cold start: $testText — waiting for Gemma...")
                lifecycleScope.launch {
                    // Wait up to 15s for Gemma to become ready
                    var waited = 0
                    while (!GemmaInferenceEngine.isReady() && waited < 15000) {
                        kotlinx.coroutines.delay(500)
                        waited += 500
                    }
                    showFullStatus()
                    Log.i(TAG, "Gemma ready=${GemmaInferenceEngine.isReady()}, sending: $testText")
                    etInput.setText(testText)
                    sendMessage()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showFullStatus()

        // Check accessibility on every resume — user might have just enabled it
        if (OmnixAccessibilityService.instance == null && !a11yWarningShown) {
            a11yWarningShown = true
            addAiMessage("⚠️ **Accessibility service not enabled.** I can still chat and run code, but I can't see or tap UI elements.\n\nTo enable: **Settings → Accessibility → Installed services → OMNIX → Enable**\n\nTap the status bar above to open settings.")
        } else if (OmnixAccessibilityService.instance != null && a11yWarningShown) {
            a11yWarningShown = false
            addAiMessage("✅ Accessibility connected! I can now see and control any app.")
        }
    }

    /**
     * Show comprehensive status: Gemma + Accessibility + Termux
     */
    private fun showFullStatus() {
        val gemmaOk = GemmaInferenceEngine.isReady()
        val a11yOk = OmnixAccessibilityService.instance != null
        val termuxOk = TermuxBridge.isTermuxInstalled(applicationContext)

        val statusParts = mutableListOf<String>()
        statusParts.add(if (gemmaOk) "🧠 AI" else "⏳ AI loading")
        statusParts.add(if (a11yOk) "👁️ A11y" else "❌ A11y")
        if (termuxOk) statusParts.add("🖥️ Termux")

        tvAiStatus.text = statusParts.joinToString(" | ")
        tvAiStatus.setTextColor(
            getColor(
                when {
                    gemmaOk && a11yOk -> R.color.omnix_green
                    gemmaOk -> R.color.omnix_yellow
                    else -> R.color.omnix_red
                }
            )
        )

        // Make status bar clickable — opens accessibility settings
        tvAiStatus.setOnClickListener {
            if (!a11yOk) {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open accessibility settings: ${e.message}")
                }
            }
        }
    }

    private fun setupInputActions() {
        btnSend.setOnClickListener { sendMessage() }

        etInput.setOnEditorActionListener { _, actionId, keyEvent ->
            val isEnter = keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER &&
                keyEvent.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnter || actionId == EditorInfo.IME_ACTION_DONE) {
                sendMessage()
                true
            } else false
        }

        btnMic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to OMNIX…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                startActivityForResult(intent, SPEECH_REQUEST)
            } catch (_: Exception) {
                addAiMessage("Voice input is not available on this device.")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestMessage(intent)
    }

    private fun handleTestMessage(intent: Intent?) {
        intent?.getStringExtra("test_message")?.let { testText ->
            if (testText.isNotBlank()) {
                Log.i(TAG, "Test message received: $testText")
                etInput.setText(testText)
                sendMessage()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                etInput.setText(spoken)
                sendMessage()
            }
        }
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        Log.i(TAG, "sendMessage: '$text'")
        hideKeyboard()
        etInput.text.clear()

        val userMsg = ChatMessage(text, isUser = true)
        adapter.addMessage(userMsg)
        scrollToBottom()
        setLoading(true)

        // Persist user message
        persistMessage(text, isUser = true)

        lifecycleScope.launch {
            var lastResponse = ""
            OmnixOrchestrator.handleChatMessageFlow(text)
                .catch { e ->
                    lastResponse = "Error: ${e.message}"
                    addAiMessage(lastResponse)
                }
                .collect { message ->
                    lastResponse = message
                    addAiMessage(message)
                    scrollToBottom()
                }
            setLoading(false)
            showFullStatus()  // Refresh status after action

            // Persist final AI response
            if (lastResponse.isNotBlank()) {
                persistMessage(lastResponse, isUser = false)
            }
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private fun persistMessage(text: String, isUser: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = OmnixDatabase.getInstance(applicationContext)

            // Create session row on first user message
            if (!sessionStarted && isUser) {
                sessionStarted = true
                firstUserMessage = text.take(60)
                val title = buildSessionTitle(firstUserMessage)
                db.chatSessionDao().insert(
                    ChatSessionEntity(
                        id = sessionId,
                        title = title,
                        startedAt = System.currentTimeMillis()
                    )
                )
            }

            if (sessionStarted) {
                db.chatMessageDao().insert(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        isUser = isUser,
                        text = text,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun finalizeSession() {
        if (!sessionStarted) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = OmnixDatabase.getInstance(applicationContext)
            val count = db.chatMessageDao().countForSession(sessionId)
            if (count == 0) return@launch
            db.chatSessionDao().finalize(
                id = sessionId,
                ts = System.currentTimeMillis(),
                count = count,
                summary = "Started: \"$firstUserMessage\""
            )
        }
    }

    private fun buildSessionTitle(firstMsg: String): String {
        val date = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val preview = firstMsg.take(40).trim()
        return if (preview.isNotBlank()) "$preview…" else "Chat – $date"
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun addAiMessage(text: String) {
        adapter.addMessage(ChatMessage(text, isUser = false))
        scrollToBottom()
    }

    private fun setLoading(loading: Boolean) {
        tvThinking.visibility = if (loading) View.VISIBLE else View.GONE
        btnSend.isEnabled = !loading
        btnMic.isEnabled  = !loading
        etInput.isEnabled = !loading
    }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0)
            rvMessages.post { rvMessages.smoothScrollToPosition(adapter.itemCount - 1) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
    }

    override fun onStop() {
        super.onStop()
        finalizeSession()
    }
}
