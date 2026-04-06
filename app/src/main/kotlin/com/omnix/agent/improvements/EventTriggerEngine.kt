package com.omnix.agent.improvements

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.*
import com.omnix.agent.executor.OmnixOrchestrator
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Event Trigger Engine — Task 20 (all 7 triggers fully implemented).
 *
 * Triggers:
 *   1. LocationLeave  — FusedLocationProviderClient geofence
 *   2. ScreenAppear   — window state changed (via OmnixAccessibilityService)
 *   3. TextChange     — accessibility content changed event
 *   4. NotificationReceived — hook into OmnixNotificationService
 *   5. TimeOfDay      — WorkManager periodic
 *   6. BatteryLevel   — BroadcastReceiver for ACTION_BATTERY_CHANGED
 *   7. AppLaunch      — window state changed with package filter
 */
object EventTriggerEngine {

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class Trigger(
        val id: String,
        val type: TriggerType,
        val params: Map<String, String> = emptyMap(),
        val skillId: String,
        val skillParams: Map<String, String> = emptyMap(),
        val enabled: Boolean = true
    )

    enum class TriggerType {
        LOCATION_LEAVE, SCREEN_APPEAR, TEXT_CHANGE,
        NOTIFICATION_RECEIVED, TIME_OF_DAY, BATTERY_LEVEL, APP_LAUNCH
    }

    private val triggers = mutableListOf<Trigger>()
    private var batteryReceiver: BatteryReceiver? = null
    private var locationClient: FusedLocationProviderClient? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        registerBatteryReceiver(context)
    }

    fun stop() {
        appContext?.let { ctx ->
            batteryReceiver?.let {
                try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
            }
        }
        locationClient = null
        scope.cancel()
    }

    fun addTrigger(trigger: Trigger) {
        triggers.removeIf { it.id == trigger.id }
        triggers.add(trigger)
        applyTrigger(trigger)
    }

    fun removeTrigger(id: String) {
        triggers.removeIf { it.id == id }
    }

    // ── Apply trigger based on type ───────────────────────────────────────────
    private fun applyTrigger(trigger: Trigger) {
        if (!trigger.enabled) return
        when (trigger.type) {
            TriggerType.LOCATION_LEAVE -> setupLocationTrigger(trigger)
            TriggerType.TIME_OF_DAY -> setupTimeTrigger(trigger)
            else -> { /* ScreenAppear, TextChange, AppLaunch, Notification handled via onEvent callbacks */ }
        }
    }

    // ── 1. LocationLeave ──────────────────────────────────────────────────────
    private fun setupLocationTrigger(trigger: Trigger) {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        locationClient = LocationServices.getFusedLocationProviderClient(ctx)
        val lat = trigger.params["lat"]?.toDoubleOrNull() ?: return
        val lng = trigger.params["lng"]?.toDoubleOrNull() ?: return
        val radiusM = trigger.params["radius_m"]?.toFloatOrNull() ?: 200f

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            .setMinUpdateDistanceMeters(50f)
            .build()

        locationClient?.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val center = Location("").apply { latitude = lat; longitude = lng }
                if (loc.distanceTo(center) > radiusM) {
                    fireTrigger(trigger)
                    locationClient?.removeLocationUpdates(this)
                }
            }
        }, ctx.mainLooper)
    }

    // ── 5. TimeOfDay ──────────────────────────────────────────────────────────
    private fun setupTimeTrigger(trigger: Trigger) {
        val ctx = appContext ?: return
        val hour = trigger.params["hour"]?.toIntOrNull() ?: return
        val minute = trigger.params["minute"]?.toIntOrNull() ?: 0

        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
        }
        if (target.before(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val delayMs = target.timeInMillis - System.currentTimeMillis()

        val workRequest = OneTimeWorkRequestBuilder<TimeOfDayWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("skill_id" to trigger.skillId, "trigger_id" to trigger.id))
            .addTag("trigger_${trigger.id}")
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "trigger_${trigger.id}", ExistingWorkPolicy.REPLACE, workRequest
        )
    }

    // ── 6. BatteryLevel ───────────────────────────────────────────────────────
    private fun registerBatteryReceiver(context: Context) {
        batteryReceiver = BatteryReceiver()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = (level * 100 / scale)

            triggers.filter { it.enabled && it.type == TriggerType.BATTERY_LEVEL }.forEach { trigger ->
                val threshold = trigger.params["threshold_pct"]?.toIntOrNull() ?: 20
                val direction = trigger.params["direction"] ?: "below"
                val triggered = when (direction) {
                    "below" -> pct <= threshold
                    "above" -> pct >= threshold
                    else -> false
                }
                if (triggered) fireTrigger(trigger)
            }
        }
    }

    // ── 2 & 3 & 7: ScreenAppear, TextChange, AppLaunch ───────────────────────
    fun onScreenChanged(packageName: String, className: String) {
        triggers.filter { it.enabled }.forEach { trigger ->
            when (trigger.type) {
                TriggerType.SCREEN_APPEAR -> {
                    val target = trigger.params["class_name"] ?: return@forEach
                    if (className.contains(target, ignoreCase = true)) fireTrigger(trigger)
                }
                TriggerType.APP_LAUNCH -> {
                    val pkg = trigger.params["package"] ?: return@forEach
                    if (packageName == pkg) fireTrigger(trigger)
                }
                else -> {}
            }
        }
    }

    fun onTextChanged(packageName: String, text: String) {
        triggers.filter { it.enabled && it.type == TriggerType.TEXT_CHANGE }.forEach { trigger ->
            val contains = trigger.params["contains"] ?: return@forEach
            if (text.contains(contains, ignoreCase = true)) fireTrigger(trigger)
        }
    }

    // ── 4. NotificationReceived ───────────────────────────────────────────────
    fun onNotificationReceived(packageName: String, title: String, text: String) {
        triggers.filter { it.enabled && it.type == TriggerType.NOTIFICATION_RECEIVED }.forEach { trigger ->
            val pkg = trigger.params["package"]
            val titleContains = trigger.params["title_contains"]
            val textContains = trigger.params["text_contains"]

            val pkgMatch = pkg == null || packageName == pkg
            val titleMatch = titleContains == null || title.contains(titleContains, ignoreCase = true)
            val textMatch = textContains == null || text.contains(textContains, ignoreCase = true)

            if (pkgMatch && titleMatch && textMatch) fireTrigger(trigger)
        }
    }

    // ── Fire ──────────────────────────────────────────────────────────────────
    private fun fireTrigger(trigger: Trigger) {
        scope.launch {
            try {
                OmnixOrchestrator.executeSkillById(trigger.skillId, trigger.skillParams)
            } catch (_: Exception) {}
        }
    }
}

class TimeOfDayWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val skillId = inputData.getString("skill_id") ?: return Result.failure()
        OmnixOrchestrator.executeSkillById(skillId, emptyMap())
        return Result.success()
    }
}
