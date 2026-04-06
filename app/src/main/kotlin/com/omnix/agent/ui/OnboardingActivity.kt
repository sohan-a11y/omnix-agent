package com.omnix.agent.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.omnix.agent.R
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.ModelDownloadManager
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.skills.CorrectionLearner
import com.omnix.agent.improvements.ProactiveAssistant
import com.omnix.agent.skills.SkillLibrary
import com.omnix.agent.voice.TTS
import com.omnix.agent.voice.VoicePipeline
import com.omnix.agent.voice.WhisperEngine
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkAndProgress() }

    private lateinit var progressBar: ProgressBar
    private lateinit var tvDownloadStatus: TextView
    private lateinit var tvVoskStatus: TextView
    private lateinit var btnDownload: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        progressBar     = findViewById(R.id.progress_download)
        tvDownloadStatus = findViewById(R.id.tv_download_status)
        tvVoskStatus    = findViewById(R.id.tv_vosk_status)
        btnDownload     = findViewById(R.id.btn_download_model)

        TTS.initialize(this)
        OmnixOrchestrator.initialize(this)
        CorrectionLearner.init(this)

        setupUI()
        requestRuntimePermissions()
        checkAndProgress()
        observeGemmaDownload()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
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
        if (perms.isNotEmpty()) permissionLauncher.launch(perms)
    }

    private fun setupUI() {
        findViewById<Button>(R.id.btn_grant_accessibility)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_grant_overlay)?.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        btnDownload.setOnClickListener { showModelDownloadDialog() }
        findViewById<Button>(R.id.btn_start)?.setOnClickListener { startOmnix() }
    }

    // ── WorkManager observation for live download progress ────────────────────
    private fun observeGemmaDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("gemma_download")
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        progressBar.visibility = View.VISIBLE
                        tvDownloadStatus.visibility = View.VISIBLE
                        progressBar.isIndeterminate = true
                        tvDownloadStatus.text = "Gemma download queued — waiting for Wi-Fi…"
                        btnDownload.isEnabled = false
                    }
                    WorkInfo.State.RUNNING -> {
                        progressBar.visibility = View.VISIBLE
                        tvDownloadStatus.visibility = View.VISIBLE
                        btnDownload.isEnabled = false
                        val pct = info.progress.getInt("pct", -1)
                        val dlMb = info.progress.getLong("downloaded_mb", 0)
                        val totMb = info.progress.getLong("total_mb", 0)
                        if (pct >= 0 && totMb > 0) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = pct
                            tvDownloadStatus.text = "$pct% — ${dlMb} MB / ${totMb} MB downloaded"
                        } else {
                            progressBar.isIndeterminate = true
                            tvDownloadStatus.text = "Connecting to HuggingFace…"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        progressBar.visibility = View.GONE
                        tvDownloadStatus.visibility = View.GONE
                        btnDownload.text = "✓ Gemma Model Ready"
                        btnDownload.isEnabled = false
                        checkAndProgress()
                    }
                    WorkInfo.State.FAILED -> {
                        progressBar.visibility = View.GONE
                        tvDownloadStatus.visibility = View.VISIBLE
                        tvDownloadStatus.text = "Download failed — check Wi-Fi and try again"
                        btnDownload.isEnabled = true
                        btnDownload.text = "Retry Download"
                    }
                    WorkInfo.State.CANCELLED -> {
                        progressBar.visibility = View.GONE
                        tvDownloadStatus.visibility = View.GONE
                        btnDownload.isEnabled = true
                    }
                }
            }
    }

    private fun checkAndProgress() {
        val hasAccessibility = isAccessibilityEnabled()
        val hasOverlay       = Settings.canDrawOverlays(this)
        val hasGemma         = ModelDownloadManager.isModelDownloaded(this)
        val hasVosk          = File(filesDir, "${WhisperEngine.MODEL_DIR}/${WhisperEngine.MODEL_FILENAME}").exists()

        updateUI(hasAccessibility, hasOverlay, hasGemma)

        // Vosk status
        tvVoskStatus.text = when {
            hasVosk -> "✓ Voice model ready"
            else    -> "Downloading voice model (~40 MB)…"
        }
        if (!hasVosk) enqueueVoskDownload()

        if (hasAccessibility && hasOverlay && hasGemma) seedDefaultSkills()
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
        btnDownload.apply {
            text = if (model) "✓ Gemma Model Ready" else "Download Gemma 4 Model (~2 GB)"
            isEnabled = !model
        }
        if (model) {
            progressBar.visibility = View.GONE
            tvDownloadStatus.visibility = View.GONE
        }
        findViewById<Button>(R.id.btn_start)?.isEnabled = accessibility && overlay
    }

    private fun isAccessibilityEnabled(): Boolean {
        val v = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return v.contains(packageName)
    }

    private fun showModelDownloadDialog() {
        AlertDialog.Builder(this)
            .setTitle("Download Gemma 4 AI Model (~2.6 GB)")
            .setMessage(
                "This will download the Gemma 4 E2B model (~2.6 GB) from HuggingFace.\n\n" +
                "✅ No account or token required — it's a public model!\n\n" +
                "Make sure you're on Wi-Fi or have enough mobile data before starting.\n\n" +
                "The download runs in the background — you can leave the app and it will continue."
            )
            .setPositiveButton("Start Download") { _, _ ->
                ModelDownloadManager.startDownload(this)
                enqueueVoskDownload()
                Toast.makeText(this, "Download started — watch progress bar above", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Download Vosk model directly to filesDir with URL.openStream().
     * DownloadManager cannot write to internal storage (SecurityException).
     */
    private fun enqueueVoskDownload() {
        val destDir = File(filesDir, WhisperEngine.MODEL_DIR).also { it.mkdirs() }
        val destZip = File(destDir, "vosk-model.zip")
        if (File(destDir, WhisperEngine.MODEL_FILENAME).exists() || destZip.exists()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "Downloading voice model (~40 MB)…"
                }
                val url = java.net.URL(
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
                )
                url.openStream().buffered().use { input ->
                    destZip.outputStream().buffered().use { input.copyTo(it) }
                }

                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "Extracting voice model…"
                }

                // Unzip it
                java.util.zip.ZipInputStream(destZip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                
                // Cleanup zip
                destZip.delete()

                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "✓ Voice model ready"
                    checkAndProgress()
                }
            } catch (e: Exception) {
                destZip.delete()
                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "Voice model download failed — check Wi-Fi"
                }
            }
        }
    }

    private fun startOmnix() {
        GemmaInferenceEngine.initialize(this)
        VoicePipeline.start(this)
        TTS.speak("OMNIX is ready. Say Hey OMNIX to start.", TTS.QUEUE_FLUSH)
        moveTaskToBack(true)
    }

    private fun seedDefaultSkills() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = OmnixDatabase.getInstance(applicationContext)
            SkillLibrary.seedAll(applicationContext, db)
            ProactiveAssistant.start(applicationContext, db)
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndProgress()
    }
}
