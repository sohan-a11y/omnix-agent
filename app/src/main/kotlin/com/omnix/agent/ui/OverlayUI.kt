package com.omnix.agent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.omnix.agent.database.SkillEntity

object OverlayUI {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String) {
        mainHandler.post {
            dismiss()
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
        }
    }

    private fun buildStatusView(context: Context, message: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC0D47A1"))
            setPadding(24, 12, 24, 12)

            val dot = TextView(context).apply {
                text = "⬤ "
                setTextColor(Color.parseColor("#00BCD4"))
                textSize = 10f
            }
            addView(dot)

            val text = TextView(context).apply {
                tag = "status_text"
                text = message
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            addView(text)
        }
    }

    private fun buildProgressView(context: Context, step: String, total: Int, current: Int): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0D47A1"))
            setPadding(24, 16, 24, 16)

            val text = TextView(context).apply {
                tag = "status_text"
                text = "OMNIX: $step"
                setTextColor(Color.WHITE)
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
