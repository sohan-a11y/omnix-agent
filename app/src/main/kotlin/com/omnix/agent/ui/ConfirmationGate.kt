package com.omnix.agent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ConfirmationGate {

    private var windowManager: WindowManager? = null
    private var confirmView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Shows an overlay confirmation dialog for high-risk actions.
     * Returns true if user confirms, false if cancelled.
     * Times out after [timeoutMs] ms and returns false.
     */
    suspend fun confirm(
        context: Context,
        skill: SkillEntity,
        params: Map<String, String>,
        timeoutMs: Long = 15_000
    ): Boolean = suspendCancellableCoroutine { cont ->
        val summary = buildSummary(skill, params)
        TTS.speak("Confirm: $summary. Say yes or tap confirm.", TTS.QUEUE_FLUSH)

        mainHandler.post {
            dismissConfirm()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val view = buildConfirmView(context, summary,
                onConfirm = {
                    dismissConfirm()
                    if (cont.isActive) cont.resume(true)
                },
                onCancel = {
                    dismissConfirm()
                    if (cont.isActive) cont.resume(false)
                }
            )

            val params2 = WindowManager.LayoutParams(
                700,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            wm.addView(view, params2)
            confirmView = view

            // Auto-cancel after timeout
            mainHandler.postDelayed({
                dismissConfirm()
                if (cont.isActive) cont.resume(false)
            }, timeoutMs)
        }

        cont.invokeOnCancellation { dismissConfirm() }
    }

    private fun dismissConfirm() {
        try {
            confirmView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // Ignore
        }
        confirmView = null
    }

    private fun buildSummary(skill: SkillEntity, params: Map<String, String>): String {
        val paramStr = params.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
        return "${skill.name}${if (paramStr.isNotEmpty()) " ($paramStr)" else ""}"
    }

    private fun buildConfirmView(
        context: Context,
        summary: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60D47A1"))
            setPadding(32, 24, 32, 24)

            val title = TextView(context).apply {
                text = "OMNIX wants to:"
                setTextColor(Color.parseColor("#90CAF9"))
                textSize = 12f
            }
            addView(title)

            val action = TextView(context).apply {
                text = summary
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 8, 0, 16)
            }
            addView(action)

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val cancelBtn = Button(context).apply {
                text = "Cancel"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { onCancel() }
            }
            buttons.addView(cancelBtn)

            val confirmBtn = Button(context).apply {
                text = "Confirm"
                setTextColor(Color.parseColor("#0D47A1"))
                setBackgroundColor(Color.WHITE)
                setOnClickListener { onConfirm() }
            }
            buttons.addView(confirmBtn)

            addView(buttons)
        }
    }
}
