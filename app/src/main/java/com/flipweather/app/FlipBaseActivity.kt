package com.flipweather.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
 *   Right softkey - Options (Settings) - on Radar specifically, this
 *                  jumps straight to the Radar Options screen instead
 *                  (see RadarOptionsActivity). Settings > Advanced can
 *                  move Options to the LEFT softkey instead (see
 *                  Prefs.isOptionsOnLeft); the other softkey does
 *                  nothing, since every screen auto-refreshes.
 *   Menu key     - also Options: the dedicated Options key on Sonim
 *                  phones sends KEYCODE_MENU. The very first press
 *                  instead asks whether to move the "Options" label to
 *                  the left (see promptOptionsSide).
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
 * Data screens (Current, Daily, Hourly, Discussion, Radar) auto-update:
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
            onTick()
            maybeAutoRefresh()
            tickHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        SystemBars.apply(this)
        placeOptionsLabel()
        if (autoRefreshIntervalMs != null) tickHandler.postDelayed(tick, 60_000L)
    }

    // Re-hide the phone's softkey bar after a dialog (e.g. the Options-key prompt) closes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onPause() {
        tickHandler.removeCallbacks(tick)
        super.onPause()
    }

    /** Runs every minute while the screen is open, before [maybeAutoRefresh]. */
    protected open fun onTick() {}

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
        // Ahead of AppCompat, which would otherwise take MENU for its own
        // (unused) options menu.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (Prefs.hasSeenMenuKey(this)) openOptions() else promptOptionsSide()
            }
            return true
        }
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
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> {
                val optionsKey = if (Prefs.isOptionsOnLeft(this)) KeyEvent.KEYCODE_SOFT_LEFT else KeyEvent.KEYCODE_SOFT_RIGHT
                if (keyCode == optionsKey) openOptions()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openOptions() {
        when {
            this is SettingsActivity -> {}
            this is RadarActivity -> startActivity(Intent(this, RadarOptionsActivity::class.java))
            else -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * First Options/Menu key press ever: offer to move the "Options" label
     * (and the Options softkey) to the left. Marked seen up front so it's
     * only ever asked once; Settings > Advanced can change it later.
     */
    private fun promptOptionsSide() {
        Prefs.setSeenMenuKey(this)
        AlertDialog.Builder(this)
            .setTitle("Options key found")
            .setMessage("Your phone has an Options key. Show the \"Options\" label on the left side of the screen instead?")
            .setPositiveButton("Yes") { _, _ ->
                Prefs.setOptionsOnLeft(this, true)
                placeOptionsLabel()
                onOptionsSideChanged()
            }
            .setNegativeButton("No", null)
            .create()
            .apply { setCanceledOnTouchOutside(false) }
            .show()
    }

    /** Called after the Options side changes from the first-press prompt. */
    protected open fun onOptionsSideChanged() {}

    /** Moves the footer's "Options" label to whichever end matches the Options softkey. */
    private fun placeOptionsLabel() {
        val label = findViewById<View>(R.id.footerOptions) ?: return
        val footer = label.parent as? ViewGroup ?: return
        val wanted = if (Prefs.isOptionsOnLeft(this)) 0 else footer.childCount - 1
        if (footer.indexOfChild(label) == wanted) return
        footer.removeView(label)
        footer.addView(label, wanted)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingLocationHelper?.onPermissionResult(requestCode, grantResults)
    }

    /**
     * Shared "update should also update GPS location if it's turned on"
     * logic, run before every auto-refresh: if the saved location came
     * from GPS, get a fresh fix before running [onDone]; otherwise
     * [onDone] runs immediately against the existing saved coordinates.
     * The fix only replaces the saved lat/lon after a real move (see
     * [movedEnough]) - setLatLon wipes every cache and the NWS gridpoint,
     * which GPS jitter alone shouldn't trigger every few minutes. A
     * failed fix still runs [onDone] against the last known coordinates.
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
                val saved = Prefs.getLatLon(this)
                if (saved == null || movedEnough(saved.first, saved.second, lat, lon)) {
                    Prefs.setLatLon(this, lat, lon)
                    Prefs.setLocationSource(this, true) // setLatLon doesn't touch this flag, but stay explicit
                }
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

/** True once two fixes are more than ~1.5 km apart (equirectangular approximation). */
fun movedEnough(lat1: Double, lon1: Double, lat2: Double, lon2: Double, thresholdKm: Double = 1.5): Boolean {
    val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return Math.sqrt(x * x + y * y) * 6371.0 > thresholdKm
}
