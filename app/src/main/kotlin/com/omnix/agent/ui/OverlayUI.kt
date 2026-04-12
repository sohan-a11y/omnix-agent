package com.omnix.agent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.omnix.agent.R
import com.omnix.agent.database.SkillEntity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating overlay that shows current task step + STOP button.
 * The STOP button sets a cancellation flag that the AutonomyLoop checks
 * between every step.
 */
object OverlayUI {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cancellation flag — checked by AutonomyLoop between steps */
    val cancelled = AtomicBoolean(false)

    /**
     * Show overlay with STOP button for autonomous task execution.
     */
    fun showWithStop(context: Context, message: String) {
        cancelled.set(false)
        mainHandler.post {
            dismiss()
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val view = buildStopView(context, message)
                val params = WindowManager.LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 100
                }

                wm.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                // Overlay permission may not be granted
                android.util.Log.w("OverlayUI", "Cannot show overlay: ${e.message}")
            }
        }
    }

    fun show(context: Context, message: String) {
        mainHandler.post {
            dismiss()
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val view = buildStatusView(context, message)
                val params = WindowManager.LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 120
                }

                wm.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                android.util.Log.w("OverlayUI", "Cannot show overlay: ${e.message}")
            }
        }
    }

    fun updateStatus(message: String) {
        mainHandler.post {
            overlayView?.findViewWithTag<TextView>("status_text")?.text = message
        }
    }

    fun dismiss() {
        mainHandler.post {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (e: Exception) {
                // View may already be removed
            }
            overlayView = null
        }
    }

    fun showProgress(context: Context, step: String, total: Int, current: Int) {
        mainHandler.post {
            dismiss()
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val view = buildProgressView(context, step, total, current)
                val params = WindowManager.LayoutParams(
                    600,
                    WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 120
                }

                wm.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                android.util.Log.w("OverlayUI", "Cannot show overlay: ${e.message}")
            }
        }
    }

    private fun buildStopView(context: Context, message: String): View {
        val dm = context.resources.displayMetrics
        val maxTextWidth = (dm.widthPixels * 0.5f).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = context.getString(R.string.cd_task_running)

            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.omnix_bg_card))
                cornerRadius = 24f
                setStroke(2, ContextCompat.getColor(context, R.color.omnix_blue))
            }
            background = bg
            setPadding(28, 14, 16, 14)

            val dot = TextView(context).apply {
                text = "● "
                setTextColor(ContextCompat.getColor(context, R.color.omnix_green))
                textSize = 12f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(dot)

            val statusTv = TextView(context).apply {
                tag = "status_text"
                text = message
                setTextColor(ContextCompat.getColor(context, R.color.omnix_text_primary))
                textSize = 13f
                maxWidth = maxTextWidth
            }
            addView(statusTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            val stopBtn = TextView(context).apply {
                text = " STOP "
                setTextColor(ContextCompat.getColor(context, R.color.omnix_text_primary))
                textSize = 12f
                contentDescription = context.getString(R.string.cd_stop_task)
                isFocusable = true
                isClickable = true
                val stopBg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.omnix_red))
                    cornerRadius = 12f
                }
                background = stopBg
                setPadding(20, 8, 20, 8)
                setOnClickListener {
                    cancelled.set(true)
                    text = " STOPPING… "
                    background = GradientDrawable().apply {
                        setColor(Color.DKGRAY)
                        cornerRadius = 12f
                    }
                    contentDescription = context.getString(R.string.cd_stopping_task)
                }
            }
            addView(stopBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = 12
            })
        }
    }

    private fun buildStatusView(context: Context, message: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.omnix_bg_card2))
                cornerRadius = 16f
            }
            background = bg
            setPadding(24, 12, 24, 12)

            val dot = TextView(context).apply {
                text = "⬤ "
                setTextColor(ContextCompat.getColor(context, R.color.omnix_accent2))
                textSize = 10f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(dot)

            val text = TextView(context).apply {
                tag = "status_text"
                text = message
                setTextColor(ContextCompat.getColor(context, R.color.omnix_text_primary))
                textSize = 13f
            }
            addView(text)
        }
    }

    private fun buildProgressView(context: Context, step: String, total: Int, current: Int): View {
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.8f).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.omnix_bg_card2))
                cornerRadius = 16f
            }
            background = bg
            setPadding(24, 16, 24, 16)
            minimumWidth = width

            val text = TextView(context).apply {
                tag = "status_text"
                text = "OMNIX: $step"
                setTextColor(ContextCompat.getColor(context, R.color.omnix_text_primary))
                textSize = 13f
            }
            addView(text)

            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total
                progress = current
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            addView(progress)
        }
    }
}
