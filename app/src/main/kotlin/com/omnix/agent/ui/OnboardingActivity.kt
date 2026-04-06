package com.omnix.agent.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.omnix.agent.R
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.ModelDownloadManager
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.skills.CorrectionLearner
import com.omnix.agent.improvements.ProactiveAssistant
import com.omnix.agent.skills.SkillLibrary
import com.omnix.agent.voice.TTS
import com.omnix.agent.voice.WhisperEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        checkAndProgress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        TTS.initialize(this)
        OmnixOrchestrator.initialize(this)
        CorrectionLearner.init(this)

        setupUI()
        checkAndProgress()
    }

    private fun setupUI() {
        // Setup button click listeners
        findViewById<Button>(R.id.btn_grant_accessibility)?.setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<Button>(R.id.btn_grant_overlay)?.setOnClickListener {
            openOverlaySettings()
        }
        findViewById<Button>(R.id.btn_download_model)?.setOnClickListener {
            showModelDownloadDialog()
        }
        findViewById<Button>(R.id.btn_start)?.setOnClickListener {
            startOmnix()
        }
    }

    private fun checkAndProgress() {
        val hasAccessibility = isAccessibilityEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasGemma = ModelDownloadManager.isModelDownloaded(this)
        val hasWhisper = File(filesDir, "${WhisperEngine.MODEL_DIR}/${WhisperEngine.MODEL_FILENAME}").exists()

        updateUI(hasAccessibility, hasOverlay, hasGemma)

        if (!hasWhisper) enqueueWhisperDownload()

        if (hasAccessibility && hasOverlay && hasGemma) {
            seedDefaultSkills()
        }
    }

    private fun updateUI(accessibility: Boolean, overlay: Boolean, model: Boolean) {
        // Update checkmarks and status
    }

    private fun isAccessibilityEnabled(): Boolean {
        val settingValue = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return settingValue.contains(packageName)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun showModelDownloadDialog() {
        AlertDialog.Builder(this)
            .setTitle("Download AI Models")
            .setMessage(
                "OMNIX needs 2 models (free, no API keys):\n\n" +
                "• Gemma 4 E2B   ~2 GB  (intent understanding)\n" +
                "• Whisper Tiny  ~75 MB  (wake word + speech recognition)\n\n" +
                "Download over Wi-Fi recommended."
            )
            .setPositiveButton("Download All") { _, _ ->
                ModelDownloadManager.startDownload(this)
                enqueueWhisperDownload()
                Toast.makeText(this, "Downloads started in background", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Download Vosk small English model via Android DownloadManager.
     * The zip is extracted to filesDir/models/vosk/ at first launch.
     * Model: vosk-model-small-en-us-0.15 (~40 MB, works well for Indian English).
     */
    private fun enqueueWhisperDownload() {
        val destDir = File(filesDir, WhisperEngine.MODEL_DIR).also { it.mkdirs() }
        val destZip = File(destDir, "vosk-model.zip")
        // Already extracted
        if (File(destDir, WhisperEngine.MODEL_FILENAME).exists()) return
        val url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        val dm = getSystemService(android.app.DownloadManager::class.java)
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            .setTitle("OMNIX: Vosk speech model")
            .setDescription("Downloading offline speech recognition model (~40 MB)")
            .setDestinationUri(android.net.Uri.fromFile(destZip))
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        dm.enqueue(req)
        // Extraction happens in OmnixVoiceService.onCreate() when model zip is present
    }

    private fun startOmnix() {
        GemmaInferenceEngine.initialize(this)
        TTS.speak("OMNIX is ready. Say Hey OMNIX to start.", TTS.QUEUE_FLUSH)
        // Navigate to main screen or minimize
        moveTaskToBack(true)
    }

    private fun seedDefaultSkills() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = OmnixDatabase.getInstance(applicationContext)
            // Seed all 15+ pre-built skills (idempotent)
            SkillLibrary.seedAll(applicationContext, db)
            // Start proactive monitoring
            ProactiveAssistant.start(applicationContext, db)
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndProgress()
    }
}
