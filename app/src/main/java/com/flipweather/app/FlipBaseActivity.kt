package com.flipweather.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * App-wide keypad scheme, active on every screen:
 *   D-pad LEFT/RIGHT - shift between Current / Daily / Radar, in that
 *                  order (Right from Current goes to Daily, Right from
 *                  Daily goes to Radar, etc.) - clamped at the ends
 *                  rather than wrapping. From a screen outside that
 *                  trio (Hourly, Town Search, Settings), LEFT jumps
 *                  straight to Current and RIGHT straight to Radar as
 *                  a quick way back.
 *   D-pad CENTER - jump to Daily Forecast (a focused list row still
 *                  gets first crack at CENTER for its own "open this"
 *                  action - Android delivers key events to a focused
 *                  child view before they ever reach here)
 *   Left softkey - Refresh (each screen decides whether a fresh fetch
 *                  is actually due - see RefreshThrottle - and whether
 *                  to re-poll GPS first, see refreshLocationIfGpsThenRun)
 *   Right softkey - Options (Settings) - on Radar specifically, this
 *                  jumps straight to the Radar Legend screen instead
 *                  (see RadarOptionsActivity)
 *
 * LEFT/RIGHT are intercepted in dispatchKeyEvent, ahead of the normal
 * view-focus dispatch, so they keep working even when a list row or
 * button holds focus. CENTER is deliberately left at the onKeyDown
 * stage below so a focused list row still gets first crack at it. The
 * one exception is an editable text field (Town Search's query box),
 * where LEFT/RIGHT should move the text cursor instead of shifting
 * screens.
 *
 * On Radar itself, LEFT/RIGHT are excluded from this shift so they're
 * free to pan the map instead - see RadarActivity.onKeyDown.
 *
 * The hardware Back key is left alone throughout, so it still does
 * normal Android back-stack navigation for child screens (Hourly,
 * Town Search, Settings) - except on Radar, which overrides it to
 * return to Daily instead of falling out of the app (see
 * RadarActivity.onBackPressed).
 *
 * Data screens (Current, Daily, Hourly, Discussion) also auto-update:
 * they show their cache instantly on resume, then [maybeAutoRefresh]
 * fetches in the background whenever that cache is older than
 * [autoRefreshIntervalMs] - checked again every minute while the
 * screen stays open. Progress and freshness show in [updateBar].
 */
abstract class FlipBaseActivity : AppCompatActivity() {

    companion object {
        // The order LEFT/RIGHT shift through when standing on one of
        // these three - Left = previous, Right = next, clamped at the
        // ends (no wraparound).
        private val MAIN_SCREENS: List<Class<out FlipBaseActivity>> = listOf(
            CurrentActivity::class.java,
            DailyForecastActivity::class.java,
            RadarActivity::class.java
        )
    }

    /** Override to perform this screen's actual refresh (network fetch). */
    open fun onRefreshKey() {}

    // --- Auto-refresh (see class doc) ---

    /** How old this screen's data may get before auto-refreshing; null = never. */
    protected open val autoRefreshIntervalMs: Long? = null

    /** When the data currently on screen was fetched; null = nothing cached. */
    protected open fun dataFetchedAt(): Long? = null

    /** Start a background fetch, bracketed by [beginFetch] / [endFetch]. */
    protected open fun startAutoFetch() {}

    protected var updateBar: UpdateBar? = null
    protected var isFetching = false
        private set

    // In-memory only, so a failing fetch (no signal) is retried once per
    // interval rather than on every one-minute tick.
    private var lastAutoAttemptMs = 0L
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            maybeAutoRefresh()
            tickHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (autoRefreshIntervalMs != null) tickHandler.postDelayed(tick, 60_000L)
    }

    override fun onPause() {
        tickHandler.removeCallbacks(tick)
        super.onPause()
    }

    /** Recolors the update bar, and kicks off a fetch if the data is due. */
    protected fun maybeAutoRefresh() {
        updateBar?.refreshColor()
        val interval = autoRefreshIntervalMs ?: return
        if (isFetching || !Prefs.hasLocation(this)) return
        val now = System.currentTimeMillis()
        if (now - (dataFetchedAt() ?: 0L) < interval) return
        if (now - lastAutoAttemptMs < interval) return
        lastAutoAttemptMs = now
        startAutoFetch()
    }

    /** Returns false (and does nothing) if a fetch is already running. */
    protected fun beginFetch(): Boolean {
        if (isFetching) return false
        isFetching = true
        updateBar?.setUpdating(true)
        return true
    }

    /** [fetchedAt] is the new data's fetch time on success, null on failure. */
    protected fun endFetch(fetchedAt: Long?) {
        isFetching = false
        updateBar?.setUpdating(false)
        if (fetchedAt != null) updateBar?.setUpdatedAt(fetchedAt) else updateBar?.refreshColor()
    }

    private var pendingLocationHelper: LocationHelper? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (this !is RadarActivity && event.action == KeyEvent.ACTION_DOWN && currentFocus !is EditText) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    shift(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    shift(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (this !is DailyForecastActivity) jump(DailyForecastActivity::class.java)
                return true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                when {
                    this is SettingsActivity -> {}
                    this is RadarActivity -> startActivity(Intent(this, RadarOptionsActivity::class.java))
                    else -> startActivity(Intent(this, SettingsActivity::class.java))
                }
                return true
            }
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                onRefreshKey()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingLocationHelper?.onPermissionResult(requestCode, grantResults)
    }

    /**
     * Shared "update should also update GPS location if it's turned on"
     * logic: if the saved location came from GPS, get a fresh fix (which
     * updates the saved lat/lon) before running [onDone]; otherwise
     * [onDone] runs immediately against the existing saved coordinates.
     * A failed GPS fix still runs [onDone] against the last known
     * coordinates rather than blocking the refresh entirely.
     */
    protected fun refreshLocationIfGpsThenRun(onDone: () -> Unit) {
        if (!Prefs.isLocationFromGps(this)) {
            onDone()
            return
        }
        val helper = LocationHelper(this)
        pendingLocationHelper = helper
        helper.requestLocation(
            onResult = { lat, lon ->
                Prefs.setLatLon(this, lat, lon)
                Prefs.setLocationSource(this, true) // setLatLon doesn't touch this flag, but stay explicit
                onDone()
            },
            onError = { onDone() }
        )
    }

    private fun shift(direction: Int) {
        val idx = MAIN_SCREENS.indexOfFirst { it.isInstance(this) }
        val target = if (idx == -1) {
            if (direction < 0) CurrentActivity::class.java else RadarActivity::class.java
        } else {
            MAIN_SCREENS.getOrNull(idx + direction)
        }
        if (target != null) jump(target)
    }

    protected fun jump(cls: Class<out FlipBaseActivity>) {
        startActivity(Intent(this, cls))
        finish()
    }
}
