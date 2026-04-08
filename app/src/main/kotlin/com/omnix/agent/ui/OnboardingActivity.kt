package com.omnix.agent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import com.omnix.agent.discovery.AppDiscoveryWorker
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.improvements.ProactiveAssistant
import com.omnix.agent.skills.CorrectionLearner
import com.omnix.agent.skills.SkillLibrary
import com.omnix.agent.voice.OmnixVoiceService
import com.omnix.agent.voice.TTS
import com.omnix.agent.voice.WhisperEngine
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkAndProgress() }

    // ── Status pill ──────────────────────────────────────────────────────────
    private lateinit var dotStatus: View
    private lateinit var tvLiveStatus: TextView

    // ── Card dots & badges ────────────────────────────────────────────────────
    private lateinit var dotAccessibility: View
    private lateinit var tvAccessibilityBadge: TextView
    private lateinit var dotOverlay: View
    private lateinit var tvOverlayBadge: TextView
    private lateinit var dotVoice: View
    private lateinit var tvVoiceBadge: TextView
    private lateinit var tvVoskStatus: TextView
    private lateinit var dotAi: View
    private lateinit var tvAiBadge: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvDownloadStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var dotDiscovery: View
    private lateinit var tvDiscoveryBadge: TextView
    private lateinit var progressDiscovery: ProgressBar
    private lateinit var tvDiscoveryStatus: TextView

    // ── Launch ────────────────────────────────────────────────────────────────
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        bindViews()
        TTS.initialize(this)
        OmnixOrchestrator.initialize(this)
        CorrectionLearner.init(this)

        setupClickListeners()
        requestRuntimePermissions()
        checkAndProgress()
        observeGemmaDownload()
        observeDiscovery()
    }

    private fun bindViews() {
        dotStatus            = findViewById(R.id.dot_status)
        tvLiveStatus         = findViewById(R.id.tv_live_status)
        dotAccessibility     = findViewById(R.id.dot_accessibility)
        tvAccessibilityBadge = findViewById(R.id.tv_accessibility_badge)
        dotOverlay           = findViewById(R.id.dot_overlay)
        tvOverlayBadge       = findViewById(R.id.tv_overlay_badge)
        dotVoice             = findViewById(R.id.dot_voice)
        tvVoiceBadge         = findViewById(R.id.tv_voice_badge)
        tvVoskStatus         = findViewById(R.id.tv_vosk_status)
        dotAi                = findViewById(R.id.dot_ai)
        tvAiBadge            = findViewById(R.id.tv_ai_badge)
        progressDownload     = findViewById(R.id.progress_download)
        tvDownloadStatus     = findViewById(R.id.tv_download_status)
        btnDownloadModel     = findViewById(R.id.btn_download_model)
        dotDiscovery         = findViewById(R.id.dot_discovery)
        tvDiscoveryBadge     = findViewById(R.id.tv_discovery_badge)
        progressDiscovery    = findViewById(R.id.progress_discovery)
        tvDiscoveryStatus    = findViewById(R.id.tv_discovery_status)
        btnStart             = findViewById(R.id.btn_start)
    }

    private fun setupClickListeners() {
        findViewById<Button>(R.id.btn_grant_accessibility)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_grant_overlay)?.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        btnDownloadModel.setOnClickListener { showModelDownloadDialog() }
        btnStart.setOnClickListener {
            requestBatteryOptimizationExemption()
            startOmnix()
        }
        findViewById<Button>(R.id.btn_open_chat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
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

    /** Ask Android and Samsung to never kill OMNIX in the background. */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) { }
            }
        }
    }

    // ── State check ───────────────────────────────────────────────────────────

    private fun checkAndProgress() {
        val hasAccessibility = isAccessibilityEnabled()
        val hasOverlay       = Settings.canDrawOverlays(this)
        val hasGemma         = ModelDownloadManager.isModelDownloaded(this)
        val hasVosk          = File(filesDir,
            "${WhisperEngine.MODEL_DIR}/${WhisperEngine.MODEL_FILENAME}").exists()

        updateDots(hasAccessibility, hasOverlay, hasVosk, hasGemma)

        if (!hasVosk) enqueueVoskDownload()

        // Seed skills as soon as accessibility + overlay are granted — no Gemma needed
        if (hasAccessibility && hasOverlay) seedDefaultSkills()
    }

    private fun updateDots(
        hasAccessibility: Boolean,
        hasOverlay: Boolean,
        hasVosk: Boolean,
        hasGemma: Boolean
    ) {
        // Accessibility
        setDot(dotAccessibility, hasAccessibility)
        tvAccessibilityBadge.text  = if (hasAccessibility) "ACTIVE" else "REQUIRED"
        tvAccessibilityBadge.setTextColor(getColor(
            if (hasAccessibility) R.color.omnix_green else R.color.omnix_red))
        findViewById<Button>(R.id.btn_grant_accessibility)?.apply {
            text      = if (hasAccessibility) "✓ Accessibility Enabled" else "Enable in Settings"
            isEnabled = !hasAccessibility
        }

        // Overlay
        setDot(dotOverlay, hasOverlay)
        tvOverlayBadge.text  = if (hasOverlay) "ACTIVE" else "REQUIRED"
        tvOverlayBadge.setTextColor(getColor(
            if (hasOverlay) R.color.omnix_green else R.color.omnix_red))
        findViewById<Button>(R.id.btn_grant_overlay)?.apply {
            text      = if (hasOverlay) "✓ Overlay Granted" else "Grant Permission"
            isEnabled = !hasOverlay
        }

        // Voice model
        if (hasVosk) {
            setDot(dotVoice, true)
            tvVoiceBadge.text = "READY"
            tvVoiceBadge.setTextColor(getColor(R.color.omnix_green))
            tvVoskStatus.text = "✓ Vosk voice model ready — wake word active"
        } else {
            dotVoice.background = getDrawable(R.drawable.bg_status_dot_yellow)
            tvVoiceBadge.text = "DOWNLOADING"
            tvVoiceBadge.setTextColor(getColor(R.color.omnix_yellow))
            tvVoskStatus.text = "Downloading voice model (~40 MB)…"
        }

        // AI model
        if (hasGemma) {
            setDot(dotAi, true)
            tvAiBadge.text = "LOADED"
            tvAiBadge.setTextColor(getColor(R.color.omnix_green))
            btnDownloadModel.text      = "✓ Gemma 4 AI Ready"
            btnDownloadModel.isEnabled = false
        } else {
            dotAi.background = getDrawable(R.drawable.bg_status_dot_yellow)
            tvAiBadge.text = "OPTIONAL"
            tvAiBadge.setTextColor(getColor(R.color.omnix_yellow))
            btnDownloadModel.text      = "Download Gemma 4 AI"
            btnDownloadModel.isEnabled = true
        }

        // Launch button — only need accessibility + overlay
        btnStart.isEnabled = hasAccessibility && hasOverlay

        // Live status pill
        val ready = hasAccessibility && hasOverlay
        dotStatus.background = getDrawable(
            if (ready) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_red)
        tvLiveStatus.text = when {
            ready && hasVosk && hasGemma -> "Fully Ready"
            ready && hasVosk             -> "Ready — AI optional"
            ready                        -> "Ready — downloading voice"
            else                         -> "Setup Required"
        }
    }

    private fun setDot(dot: View, ok: Boolean) {
        dot.background = getDrawable(
            if (ok) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_red)
    }

    // ── WorkManager observers ─────────────────────────────────────────────────

    private fun observeGemmaDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("gemma_download")
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        progressDownload.visibility   = View.VISIBLE
                        tvDownloadStatus.visibility   = View.VISIBLE
                        progressDownload.isIndeterminate = true
                        tvDownloadStatus.text         = "Queued — waiting for network…"
                        btnDownloadModel.isEnabled    = false
                    }
                    WorkInfo.State.RUNNING -> {
                        progressDownload.visibility   = View.VISIBLE
                        tvDownloadStatus.visibility   = View.VISIBLE
                        btnDownloadModel.isEnabled    = false
                        val pct   = info.progress.getInt("pct", -1)
                        val dlMb  = info.progress.getLong("downloaded_mb", 0)
                        val totMb = info.progress.getLong("total_mb", 0)
                        if (pct >= 0 && totMb > 0) {
                            progressDownload.isIndeterminate = false
                            progressDownload.progress        = pct
                            tvDownloadStatus.text            = "$pct% — ${dlMb}MB / ${totMb}MB"
                            dotAi.background = getDrawable(R.drawable.bg_status_dot_yellow)
                            tvAiBadge.text = "$pct%"
                        } else {
                            progressDownload.isIndeterminate = true
                            tvDownloadStatus.text            = "Connecting to HuggingFace…"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        progressDownload.visibility   = View.GONE
                        tvDownloadStatus.visibility   = View.GONE
                        btnDownloadModel.text         = "✓ Gemma 4 AI Ready"
                        btnDownloadModel.isEnabled    = false
                        setDot(dotAi, true)
                        tvAiBadge.text = "LOADED"
                        tvAiBadge.setTextColor(getColor(R.color.omnix_green))
                        checkAndProgress()
                    }
                    WorkInfo.State.FAILED -> {
                        progressDownload.visibility = View.GONE
                        tvDownloadStatus.visibility = View.VISIBLE
                        tvDownloadStatus.text       = "Download failed — tap to retry"
                        btnDownloadModel.isEnabled  = true
                        btnDownloadModel.text       = "Retry Download"
                        setDot(dotAi, false)
                    }
                    WorkInfo.State.CANCELLED -> {
                        progressDownload.visibility = View.GONE
                        tvDownloadStatus.visibility = View.GONE
                        btnDownloadModel.isEnabled  = true
                    }
                }
            }
    }

    private fun observeDiscovery() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(AppDiscoveryWorker.WORK_TAG)
            .observe(this) { infos ->
                if (infos.isNullOrEmpty()) return@observe
                // infos is a chain — check the first non-succeeded one for progress
                val active = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                val allDone = infos.all { it.state == WorkInfo.State.SUCCEEDED }

                when {
                    allDone -> {
                        progressDiscovery.visibility = View.GONE
                        setDot(dotDiscovery, true)
                        tvDiscoveryBadge.text = "COMPLETE"
                        tvDiscoveryBadge.setTextColor(getColor(R.color.omnix_green))
                        tvDiscoveryStatus.text = "✓ All apps learned — knowledge base ready"
                    }
                    active != null -> {
                        progressDiscovery.visibility = View.VISIBLE
                        dotDiscovery.background = getDrawable(R.drawable.bg_status_dot_yellow)
                        tvDiscoveryBadge.text = "LEARNING"
                        tvDiscoveryBadge.setTextColor(getColor(R.color.omnix_yellow))
                        val done  = active.progress.getInt("done", 0)
                        val total = active.progress.getInt("total", 0)
                        val pkg   = active.progress.getString("current_pkg") ?: ""
                        if (total > 0) {
                            progressDiscovery.isIndeterminate = false
                            progressDiscovery.progress = (done * 100 / total)
                            tvDiscoveryStatus.text = "Learning apps: $done / $total"
                        } else {
                            progressDiscovery.isIndeterminate = true
                            tvDiscoveryStatus.text = if (pkg.isNotBlank()) "Reading $pkg…" else "Scanning apps…"
                        }
                    }
                    else -> {
                        // Some jobs might have failed — show partial state
                        progressDiscovery.visibility = View.GONE
                        dotDiscovery.background = getDrawable(R.drawable.bg_status_dot_yellow)
                        tvDiscoveryStatus.text = "Learning in progress…"
                    }
                }
            }
    }

    // ── Model download dialog ─────────────────────────────────────────────────

    private fun showModelDownloadDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Download Gemma 4 AI Model (~2.6 GB)")
            .setMessage(
                "Downloads the Gemma 4 E2B LiteRT model from HuggingFace.\n\n" +
                "This is a PUBLIC model — no account or token needed.\n\n" +
                "Make sure you are on Wi-Fi. The download continues in the background."
            )
            .setPositiveButton("Start Download") { _, _ ->
                ModelDownloadManager.startDownload(this)
                Toast.makeText(this, "Download started — watch progress above", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Vosk download ─────────────────────────────────────────────────────────

    private fun enqueueVoskDownload() {
        val destDir = File(filesDir, WhisperEngine.MODEL_DIR).also { it.mkdirs() }
        val destZip = File(destDir, "vosk-model.zip")
        val extracted = File(destDir, WhisperEngine.MODEL_FILENAME)
        if (extracted.exists() || destZip.exists()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "Downloading voice model (~40 MB)…"
                    dotVoice.background = getDrawable(R.drawable.bg_status_dot_yellow)
                    tvVoiceBadge.text = "DOWNLOADING"
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

                java.util.zip.ZipInputStream(destZip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                destZip.delete()

                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "✓ Voice model ready"
                    setDot(dotVoice, true)
                    tvVoiceBadge.text = "READY"
                    tvVoiceBadge.setTextColor(getColor(R.color.omnix_green))
                    checkAndProgress()
                }
            } catch (e: Exception) {
                destZip.delete()
                withContext(Dispatchers.Main) {
                    tvVoskStatus.text = "Voice model download failed — check Wi-Fi"
                    setDot(dotVoice, false)
                }
            }
        }
    }

    // ── Launch OMNIX ──────────────────────────────────────────────────────────

    private fun startOmnix() {
        // Start foreground mic service — it will init Gemma + VoicePipeline inside
        val voiceIntent = Intent(this, OmnixVoiceService::class.java)
        startForegroundService(voiceIntent)

        // Enqueue full WorkManager-based discovery (Samsung-safe, chained batches)
        AppDiscoveryWorker.enqueueFullDiscovery(this)

        // Show "AI loading" in dot if model exists but Gemma not ready yet
        if (ModelDownloadManager.isModelDownloaded(this) && !GemmaInferenceEngine.isReady()) {
            dotAi.background = getDrawable(R.drawable.bg_status_dot_yellow)
            tvAiBadge.text = "LOADING…"
            tvAiBadge.setTextColor(getColor(R.color.omnix_yellow))
        }

        TTS.speak("OMNIX is starting. Say Hi AI once the brain is ready.", TTS.QUEUE_FLUSH)
        moveTaskToBack(true)
    }

    // ── Skills seeding ────────────────────────────────────────────────────────

    private fun seedDefaultSkills() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = OmnixDatabase.getInstance(applicationContext)
            SkillLibrary.seedAll(applicationContext, db)
            ProactiveAssistant.start(applicationContext, db)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val v = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return v.contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        checkAndProgress()
    }
}
