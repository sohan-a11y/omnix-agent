package com.omnix.agent.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.delay

/**
 * Emergency SOS workflow.
 * Triggered by voice: "Emergency" or "SOS" or "Help me"
 *
 * Workflow:
 * 1. Announce SOS activation
 * 2. Send SMS to emergency contacts
 * 3. Call emergency number
 * 4. Share location
 */
class EmergencySOSSkill(private val context: Context) {

    companion object {
        val EMERGENCY_TRIGGER_PHRASES = listOf(
            "emergency", "sos", "help me", "call 911", "i need help"
        )
    }

    suspend fun activate(emergencyContacts: List<String>, emergencyNumber: String = "911") {
        TTS.speak("Activating emergency SOS. Notifying contacts.", TTS.QUEUE_FLUSH)
        delay(1000)

        // Send SMS to emergency contacts
        emergencyContacts.forEach { contact ->
            sendEmergencySMS(contact)
        }

        // Call emergency number after short delay
        delay(2000)
        makeEmergencyCall(emergencyNumber)
    }

    private fun sendEmergencySMS(phoneNumber: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val message = "EMERGENCY: OMNIX user needs help. This is an automated SOS message."
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        } catch (e: Exception) {
            // SMS failed - try via messaging app
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", "EMERGENCY SOS - Need immediate help")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(smsIntent)
        }
    }

    private fun makeEmergencyCall(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(callIntent)
    }
}
