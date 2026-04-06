package com.omnix.agent.discovery

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.omnix.agent.core.OmnixAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device integration test UI for app discovery.
 * Task 6: Tests WhatsApp, HDFC Bank, Google Maps crawl.
 */
class DiscoveryTestActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var logView: TextView

    private val testApps = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.google.android.apps.maps" to "Google Maps",
        "com.hdfcbank.mobilebanking" to "HDFC Bank"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        logView = TextView(this).apply { textSize = 12f }

        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        layout.addView(scroll)

        testApps.forEach { (pkg, name) ->
            val btn = Button(this).apply {
                text = "Crawl $name"
                setOnClickListener { startCrawl(pkg, name) }
            }
            layout.addView(btn)
        }

        setContentView(layout)
    }

    private fun startCrawl(packageId: String, name: String) {
        scope.launch {
            log("Starting: $name")
            val a11y = OmnixAccessibilityService.instance
            if (a11y == null) {
                log("ERROR: Enable OMNIX accessibility service first")
                return@launch
            }
            val engine = DiscoveryEngine(this@DiscoveryTestActivity)
            try {
                val crawls = withContext(Dispatchers.IO) {
                    engine.crawlAppWithAPKGuide(packageId, a11y, maxScreens = 5)
                }
                log("$name: ${crawls.size} screens")
                val skills = withContext(Dispatchers.IO) {
                    engine.generateSkillsFromNavPaths(packageId)
                }
                log("$name: $skills skills generated")
            } catch (e: Exception) {
                log("$name ERROR: ${e.message}")
            }
        }
    }

    private fun log(msg: String) = runOnUiThread { logView.append("$msg\n") }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
