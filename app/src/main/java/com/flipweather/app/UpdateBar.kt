package com.flipweather.app

import android.app.Activity
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Drives the "Updated h:mm" strip (layout/view_update_bar) at the top of
 * each data screen. The time digits are green while the data is fresh,
 * red once it's more than [STALE_MS] old, and a small arrow spins next
 * to them while a fetch is in flight. Radar only shows the bar while
 * it's still loading, always in red ([alwaysRed]).
 */
class UpdateBar(activity: Activity, private val alwaysRed: Boolean = false) {

    companion object {
        const val STALE_MS = 20 * 60 * 1000L
    }

    private val timeView: TextView = activity.findViewById(R.id.updateTime)
    private val spinner: ImageView = activity.findViewById(R.id.updateSpinner)
    private val freshColor = ContextCompat.getColor(activity, R.color.status_fresh)
    private val staleColor = ContextCompat.getColor(activity, R.color.status_stale)

    private var updatedAt: Long? = null

    fun setUpdatedAt(ms: Long?) {
        updatedAt = ms
        timeView.text = if (ms == null || ms <= 0L) "--:--" else formatTime(ms)
        refreshColor()
    }

    /** Re-evaluates fresh/stale - called on every auto-refresh tick. */
    fun refreshColor() {
        val at = updatedAt
        val fresh = !alwaysRed && at != null && at > 0L && System.currentTimeMillis() - at <= STALE_MS
        timeView.setTextColor(if (fresh) freshColor else staleColor)
    }

    fun setUpdating(updating: Boolean) {
        if (updating) {
            if (spinner.visibility == View.VISIBLE) return
            spinner.visibility = View.VISIBLE
            spinner.startAnimation(
                RotateAnimation(
                    0f, 360f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 900L
                    repeatCount = Animation.INFINITE
                    interpolator = LinearInterpolator()
                }
            )
        } else {
            spinner.clearAnimation()
            spinner.visibility = View.GONE
        }
    }

    private fun formatTime(ms: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "h:mm a" else "MMM d, h:mm a"
        return SimpleDateFormat(pattern, Locale.US).format(Date(ms))
    }
}
