package com.omnix.agent.improvements

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

/**
 * Event Trigger Engine - 7 trigger types:
 * 1. screen_appear - when a specific screen opens
 * 2. text_change - when specific text appears/changes
 * 3. notification - when a notification arrives
 * 4. schedule - at a specific time
 * 5. battery - on battery level change
 * 6. location - when at a location
 * 7. app_launch - when an app is launched
 */
object EventTriggerEngine {

    private lateinit var db: OmnixDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val triggers = mutableListOf<EventTrigger>()

    fun start(context: Context) {
        db = OmnixDatabase.getInstance(context)
        loadTriggers()
    }

    fun stop() {
        scope.cancel()
    }

    private fun loadTriggers() {
        // Load persisted triggers from DB
        // Default triggers for common patterns
        triggers.addAll(getDefaultTriggers())
    }

    suspend fun checkScreenTriggers(packageName: String, className: String) {
        triggers.filter { it.type == "screen_appear" && it.enabled }
            .forEach { trigger ->
                if (trigger.condition.packageName == packageName ||
                    trigger.condition.className == className
                ) {
                    fireTrigger(trigger)
                }
            }
    }

    suspend fun checkContentTriggers(packageName: String) {
        triggers.filter { it.type == "text_change" && it.enabled }
            .forEach { trigger ->
                if (trigger.condition.packageName == packageName) {
                    // Check if target text appeared
                }
            }
    }

    private suspend fun fireTrigger(trigger: EventTrigger) {
        // Execute the trigger's associated skill or action
    }

    fun addTrigger(trigger: EventTrigger) {
        triggers.add(trigger)
    }

    fun removeTrigger(triggerId: String) {
        triggers.removeAll { it.id == triggerId }
    }

    private fun getDefaultTriggers(): List<EventTrigger> = emptyList()
}

@Serializable
data class EventTrigger(
    val id: String,
    val type: String, // screen_appear | text_change | notification | schedule | battery | location | app_launch
    val name: String,
    val condition: TriggerCondition,
    val actionSkillId: String,
    val enabled: Boolean = true
)

@Serializable
data class TriggerCondition(
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val notificationApp: String = "",
    val cronExpression: String = "",
    val batteryLevel: Int = 0,
    val locationLat: Double = 0.0,
    val locationLon: Double = 0.0
)
