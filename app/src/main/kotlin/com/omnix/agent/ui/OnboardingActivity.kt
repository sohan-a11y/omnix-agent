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
        requestRuntimePermissions()
        checkAndProgress()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
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
        findViewById<Button>(R.id.btn_grant_accessibility)?.apply {
            text = if (accessibility) "✓ Accessibility Enabled" else "Open Accessibility Settings"
            isEnabled = !accessibility
        }
        findViewById<Button>(R.id.btn_grant_overlay)?.apply {
            text = if (overlay) "✓ Overlay Granted" else "Grant Overlay Permission"
            isEnabled = !overlay
        }
        findViewById<Button>(R.id.btn_download_model)?.apply {
            text = if (model) "✓ Gemma Model Ready" else "Download Gemma 4 Model (~2 GB)"
            isEnabled = !model
        }
        findViewById<Button>(R.id.btn_start)?.isEnabled = accessibility && overlay
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
     * Download Vosk small English model directly to filesDir using a coroutine.
     * DownloadManager cannot write to internal storage (SecurityException), so we
     * use URL.openStream() instead — works for any internal path, no permissions needed.
     */
    private fun enqueueWhisperDownload() {
        val destDir = File(filesDir, WhisperEngine.MODEL_DIR).also { it.mkdirs() }
        val destZip = File(destDir, "vosk-model.zip")
        if (File(destDir, WhisperEngine.MODEL_FILENAME).exists() || destZip.exists()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Downloading speech model (~40 MB)…", Toast.LENGTH_LONG
                    ).show()
                }
                val url = java.net.URL(
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
                )
                url.openStream().buffered().use { input ->
                    destZip.outputStream().buffered().use { input.copyTo(it) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Speech model ready. Restart OMNIX to activate voice.", Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                destZip.delete()   // clean up partial file
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Model download failed — check Wi-Fi and try again.", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
