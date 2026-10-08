package com.flipweather.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pannable, zoomable, ANIMATED radar: a NOAA-sourced NEXRAD composite
 * reflectivity mosaic served as plain XYZ tiles by the Iowa
 * Environmental Mesonet (mesonet.agron.iastate.edu) - free, no key -
 * drawn over a raster base map by [RadarMapView], a plain-Canvas tile
 * view (no OpenGL - MapLibre's GL renderer crashed on the E4610).
 *
 * Animation approach: IEM's tile service accepts a time-offset suffix
 * on the same URL template (e.g. "900913-m15m" = 15 minutes ago,
 * "900913" = now) - see FRAME_OFFSETS. RadarMapView fetches every
 * frame's tiles up front, so each animation tick is just a redraw
 * from its tile cache with a different frame on top of the base map.
 *
 * D-pad CENTER is claimed app-wide for switching to Daily (see
 * FlipBaseActivity), but on this screen it's repurposed to play/stop
 * the animation instead - LEFT/RIGHT are also exempted from the
 * app-wide screen-shift while this screen is up, so they pan instead:
 *   D-pad left/right - pan west / east
 *   D-pad up/down    - pan north / south
 *   *  / #           - zoom out / in
 *   5                - re-center
 *   OK / center      - play / stop the animation (stopping snaps back
 *                      to the current/"Now" frame). Doesn't auto-play
 *                      when the screen first opens - starts stopped on
 *                      "Now" until OK is pressed.
 *   Left softkey     - refresh tiles (rate-limited, see RefreshThrottle;
 *                      also re-polls GPS first if the saved location
 *                      came from GPS - see refreshLocationIfGpsThenRun)
 *   Right softkey    - Options - jumps straight to the Radar Legend
 *                      screen (RadarOptionsActivity), skipping Settings
 *   Back/Clr         - return to Daily (not the default finish-the-app
 *                      behavior a bare hardware Back key would otherwise
 *                      get here, since arriving via the app-wide shift
 *                      leaves no real back-stack entry beneath Radar)
 */
class RadarActivity : FlipBaseActivity() {

    companion object {
        private const val DEFAULT_ZOOM = 7
        private const val PAN_FRACTION = 0.175 // fraction of screen dimension per key press

        // Oldest to newest - "" means the current/latest frame. 5-minute
        // steps back to 30 minutes ago, matching IEM's own suffix format
        // (e.g. "900913-m15m"). 7 frames keeps memory/tile-fetch cost
        // reasonable for this hardware while still reading as a real loop.
        private val FRAME_OFFSETS = listOf("m30m", "m25m", "m20m", "m15m", "m10m", "m05m", "")
        private const val FRAME_INTERVAL_MS = 500L
        private const val PAUSE_ON_LATEST_MS = 1500L // brief hold on "Now" before looping
    }

    private lateinit var mapView: RadarMapView
    private lateinit var status: TextView
    private lateinit var sliderTrack: View
    private lateinit var sliderThumb: View

    private var currentFrameIndex = 0
    private var isPlaying = false
    private var animationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar)

        status = findViewById(R.id.radarStatus)
        sliderTrack = findViewById(R.id.radarSliderTrack)
        sliderThumb = findViewById(R.id.radarSliderThumb)
        mapView = findViewById(R.id.mapView)

        if (!Prefs.hasLocation(this)) {
            status.text = "No location set - go to Daily then Settings to set one"
            return
        }

        val (lat, lon) = Prefs.getLatLon(this)!!
        mapView.setHome(lat, lon)
        mapView.recenter(DEFAULT_ZOOM)
        mapView.radarUrlTemplates = FRAME_OFFSETS.map { tileUrlForOffset(it) }
        currentFrameIndex = FRAME_OFFSETS.lastIndex
        mapView.frameIndex = currentFrameIndex
        status.text = "OK=Play/Stop"
        updateSlider() // start stopped on "Now" - animation begins only once OK is pressed
    }

    override fun onRefreshKey() {
        if (!RefreshThrottle.canRefresh(this, "radar", RefreshThrottle.RADAR_MIN_MS)) {
            status.text = RefreshThrottle.waitMessage(this, "radar", RefreshThrottle.RADAR_MIN_MS)
            return
        }
        RefreshThrottle.markRefreshed(this, "radar")
        refreshLocationIfGpsThenRun {
            val (lat, lon) = Prefs.getLatLon(this) ?: return@refreshLocationIfGpsThenRun
            mapView.setHome(lat, lon)
            mapView.recenter(mapView.zoom)
            // Old frames re-resolve against the current time; base map tiles stay cached.
            mapView.reloadRadar()
            status.text = "OK=Play/Stop"
        }
    }

    private fun tileUrlForOffset(offset: String): String {
        val suffix = if (offset.isEmpty()) "900913" else "900913-$offset"
        return "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-$suffix/{z}/{x}/{y}.png"
    }

    private fun showFrame(index: Int) {
        mapView.frameIndex = index
        updateSlider()
    }

    private fun setPlaying(playing: Boolean) {
        isPlaying = playing
        animationJob?.cancel()
        if (playing) {
            animationJob = lifecycleScope.launch {
                while (isActive) {
                    // Advance BEFORE delaying, not after - playback always starts
                    // from the "Now" frame (see togglePlayback), and delaying
                    // first would mean sitting on the same still frame for the
                    // full hold (up to PAUSE_ON_LATEST_MS) with zero visible
                    // change, reading as if OK did nothing.
                    currentFrameIndex = (currentFrameIndex + 1) % FRAME_OFFSETS.size
                    showFrame(currentFrameIndex)
                    val holdMs = if (currentFrameIndex == FRAME_OFFSETS.lastIndex) PAUSE_ON_LATEST_MS else FRAME_INTERVAL_MS
                    delay(holdMs)
                }
            }
        }
    }

    /** OK/center: play if stopped; if playing, stop and snap back to the current ("Now") frame. */
    private fun togglePlayback() {
        if (!Prefs.hasLocation(this)) return
        if (isPlaying) {
            setPlaying(false)
            currentFrameIndex = FRAME_OFFSETS.lastIndex
            showFrame(currentFrameIndex)
        } else {
            setPlaying(true)
        }
    }

    /** Positions the thumb across the track to reflect currentFrameIndex, instead of a text countdown. */
    private fun updateSlider() {
        if (sliderTrack.width == 0) {
            sliderTrack.post { updateSlider() }
            return
        }
        val range = (FRAME_OFFSETS.size - 1).coerceAtLeast(1)
        val fraction = currentFrameIndex.toFloat() / range.toFloat()
        // Thumb's laid-out rest position already sits at the track's left padding
        // (FrameLayout child), so translationX only needs the fraction of the
        // remaining travel distance - not the padding offset again.
        val maxTranslation = (sliderTrack.width - sliderTrack.paddingLeft - sliderTrack.paddingRight - sliderThumb.width).toFloat()
        sliderThumb.translationX = fraction * maxTranslation
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercepted ahead of the normal view-focus dispatch (same trick
        // FlipBaseActivity uses for LEFT/RIGHT), so a focused view's own
        // DPAD_CENTER/ENTER click handling can never swallow OK before it
        // reaches here. Handling it here also takes it over from
        // FlipBaseActivity.onKeyDown's app-wide "jump to Daily".
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            togglePlayback()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!Prefs.hasLocation(this)) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            // D-pad LEFT/RIGHT are excluded from the app-wide screen-shift
            // in FlipBaseActivity while on this screen (see its
            // dispatchKeyEvent), so they land here instead and pan.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                mapView.panBy(-PAN_FRACTION, 0.0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                mapView.panBy(PAN_FRACTION, 0.0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                mapView.panBy(0.0, -PAN_FRACTION)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mapView.panBy(0.0, PAN_FRACTION)
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                mapView.zoomBy(-1)
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                mapView.zoomBy(1)
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                mapView.recenter(DEFAULT_ZOOM)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        jump(DailyForecastActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) setPlaying(true) // restart the loop if it was cancelled by onPause
    }

    override fun onPause() {
        animationJob?.cancel() // don't keep ticking while off-screen
        super.onPause()
    }
}
