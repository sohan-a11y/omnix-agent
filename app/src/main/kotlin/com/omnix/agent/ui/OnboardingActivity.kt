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
import com.omnix.agent.skills.BankingSkills
import com.omnix.agent.voice.TTS
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
        val hasModel = ModelDownloadManager.isModelDownloaded(this)

        updateUI(hasAccessibility, hasOverlay, hasModel)

        if (hasAccessibility && hasOverlay && hasModel) {
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
            .setTitle("Download Gemma 4 Model")
            .setMessage("OMNIX requires the Gemma 4 E2B model (~2GB). Download now?")
            .setPositiveButton("Download") { _, _ ->
                // Show model URL input or use default
                ModelDownloadManager.startDownload(this)
                Toast.makeText(this, "Download started in background", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            // Seed banking skills
            db.skillDao().upsert(BankingSkills.getHDFCBalanceSkill())
            db.skillDao().upsert(BankingSkills.getSBIBalanceSkill())
            db.skillDao().upsert(BankingSkills.getGPayTransferSkill())
            db.skillDao().upsert(BankingSkills.getPhonePeTransferSkill())
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndProgress()
    }
}
