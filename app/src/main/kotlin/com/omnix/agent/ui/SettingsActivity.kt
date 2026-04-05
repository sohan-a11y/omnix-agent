package com.omnix.agent.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var db: OmnixDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        db = OmnixDatabase.getInstance(this)

        setupSections()
        loadSkillLibrary()
    }

    private fun setupSections() {
        // Section 1: Voice Settings
        setupVoiceSection()

        // Section 2: Privacy & Security
        setupPrivacySection()

        // Section 3: Battery Optimization
        setupBatterySection()

        // Section 4: App Discovery
        setupDiscoverySection()
    }

    private fun setupVoiceSection() {
        // Wake phrase selector
        val wakePhrase = findViewById<Spinner>(R.id.spinner_wake_phrase)
        val phrases = arrayOf("Hey OMNIX", "OK OMNIX", "OMNIX")
        wakePhrase?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, phrases)
    }

    private fun setupPrivacySection() {
        // Financial action confirmation toggle
        val confirmFinancial = findViewById<Switch>(R.id.switch_confirm_financial)
        confirmFinancial?.isChecked = true // Default on

        // Action history retention
        val retentionDays = findViewById<SeekBar>(R.id.seekbar_retention)
        retentionDays?.progress = 30 // Default 30 days
    }

    private fun setupBatterySection() {
        // Background discovery schedule
        val discoverySchedule = findViewById<Spinner>(R.id.spinner_discovery_schedule)
        val schedules = arrayOf("While charging only", "Daily", "Every 3 days")
        discoverySchedule?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, schedules)
    }

    private fun setupDiscoverySection() {
        // Trigger full re-discovery
        val rediscoverBtn = findViewById<Button>(R.id.btn_rediscover_all)
        rediscoverBtn?.setOnClickListener {
            // Trigger background discovery for all apps
            Toast.makeText(this, "Discovery started in background", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSkillLibrary() {
        lifecycleScope.launch {
            val skills = withContext(Dispatchers.IO) {
                db.skillDao().getByCategory("banking") +
                    db.skillDao().getByCategory("payments") +
                    db.skillDao().getByCategory("messaging")
            }
            // Update RecyclerView with skill list
        }
    }
}
