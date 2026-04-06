package com.omnix.agent.skills

import android.content.Context
import android.telephony.SmsManager
import android.os.Build
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*

/**
 * Emergency SOS Workflow — Task 26.
 * Parallel coroutines: SMS + call + location — all within 5-second constraint.
 * Financial safety: no confirmation required for SOS.
 */
object EmergencyWorkflow {

    data class SOSResult(
        val smsSent: Boolean,
        val callInitiated: Boolean,
        val locationShared: Boolean,
        val durationMs: Long
    )

    /**
     * Execute full SOS: send SMS, initiate call, share location — in parallel.
     * Hard timeout: 5000ms per spec.
     */
    suspend fun executeSOS(context: Context, emergencyContact: String, location: String = ""): SOSResult {
        val start = System.currentTimeMillis()
        TTS.speak("Emergency. Contacting ${emergencyContact}.", TTS.QUEUE_FLUSH)

        val (smsSent, callInitiated, locationShared) = withContext(Dispatchers.IO) {
            val smsDeferred = async { sendSOSSms(context, emergencyContact, location) }
            val callDeferred = async { initiateSOSCall(context, emergencyContact) }
            val locationDeferred = async { shareLocation(context, emergencyContact, location) }

            // All three must complete within 5 seconds total
            withTimeoutOrNull(4500L) {
                Triple(
                    smsDeferred.await(),
                    callDeferred.await(),
                    locationDeferred.await()
                )
            } ?: Triple(false, false, false)
        }

        val duration = System.currentTimeMillis() - start
        return SOSResult(smsSent, callInitiated, locationShared, duration)
    }

    private suspend fun sendSOSSms(context: Context, contact: String, location: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val msg = buildString {
                    append("SOS! I need help. ")
                    if (location.isNotBlank()) append("My location: $location ")
                    append("- Sent via OMNIX Emergency")
                }
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                // Resolve phone number via ContactsReader
                val phone = resolvePhone(context, contact)
                if (phone.isNullOrBlank()) return@withContext false

                smsManager?.sendTextMessage(phone, null, msg, null, null)
                true
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun initiateSOSCall(context: Context, contact: String): Boolean =
        withContext(Dispatchers.Main) {
            try {
                val phone = resolvePhone(context, contact) ?: return@withContext false
                val a11y = OmnixAccessibilityService.instance ?: return@withContext false
                // Use deep link intent to initiate call
                val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$phone")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                true
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun shareLocation(context: Context, contact: String, location: String): Boolean =
        withContext(Dispatchers.IO) {
            if (location.isBlank()) return@withContext false
            // Share location via SMS as maps link
            try {
                val phone = resolvePhone(context, contact) ?: return@withContext false
                val mapsLink = "https://maps.google.com/?q=$location"
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager?.sendTextMessage(phone, null, "My live location: $mapsLink", null, null)
                true
            } catch (e: Exception) {
                false
            }
        }

    private fun resolvePhone(context: Context, contactOrPhone: String): String? {
        // If it looks like a phone number, use directly
        val digitsOnly = contactOrPhone.filter { it.isDigit() }
        if (digitsOnly.length >= 10) return digitsOnly

        // Otherwise resolve via ContactsReader
        return ContactsReader.resolve(context, contactOrPhone)?.phone
    }
}
