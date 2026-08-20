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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Pannable, zoomable, ANIMATED radar using MapLibre (rendering) + a
 * NOAA-sourced NEXRAD composite reflectivity mosaic served as plain XYZ
 * tiles by the Iowa Environmental Mesonet (mesonet.agron.iastate.edu) -
 * free, no key.
 *
 * Animation approach: IEM's tile service accepts a time-offset suffix
 * on the same URL template we already use (e.g. "900913-m15m" = 15
 * minutes ago, "900913" = now) - see FRAME_OFFSETS. Rather than
 * swapping one layer's tiles per frame (which would mean removing and
 * re-adding a source every tick - a visible flicker/reload each time),
 * all frames are added as separate raster sources/layers UP FRONT, all
 * stacked on the base map, and the animation just flips each frame
 * layer's raster-opacity between 0 and 1 on a timer. That keeps every
 * tick to a cheap paint-property change on the radar layers only - the
 * base map underneath (OpenFreeMap "Liberty" - streets, labels, etc.)
 * is loaded once and never touched again.
 *
 * Base map: OpenFreeMap's "Liberty" style (free, no key, unlimited use).
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
        private const val BASE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val DEFAULT_ZOOM = 7.0
        private const val PAN_FRACTION = 0.175 // fraction of screen dimension per key press
        private const val ZOOM_STEP = 0.5 // zoom level change per */# key press

        // Oldest to newest - "" means the current/latest frame. 5-minute
        // steps back to 30 minutes ago, matching IEM's own suffix format
        // (e.g. "900913-m15m"). 7 frames keeps memory/tile-fetch cost
        // reasonable for this hardware while still reading as a real loop.
        private val FRAME_OFFSETS = listOf("m30m", "m25m", "m20m", "m15m", "m10m", "m05m", "")
        private const val FRAME_INTERVAL_MS = 500L
        private const val PAUSE_ON_LATEST_MS = 1500L // brief hold on "Now" before looping
    }

    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var sliderTrack: View
    private lateinit var sliderThumb: View
    private var mapLibreMap: MapLibreMap? = null
    private var homeLatLng: LatLng? = null

    private val frameSourceIds = mutableListOf<String>()
    private val frameLayerIds = mutableListOf<String>()
    private var currentFrameIndex = 0
    private var isPlaying = false
    private var animationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar)

        status = findViewById(R.id.radarStatus)
        sliderTrack = findViewById(R.id.radarSliderTrack)
        sliderThumb = findViewById(R.id.radarSliderThumb)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        if (!Prefs.hasLocation(this)) {
            status.text = "No location set - go to Daily then Settings to set one"
            return
        }

        val (lat, lon) = Prefs.getLatLon(this)!!
        homeLatLng = LatLng(lat, lon)
        loadTiles()
    }

    override fun onRefreshKey() {
        if (!RefreshThrottle.canRefresh(this, "radar", RefreshThrottle.RADAR_MIN_MS)) {
            status.text = RefreshThrottle.waitMessage(this, "radar", RefreshThrottle.RADAR_MIN_MS)
            return
        }
        RefreshThrottle.markRefreshed(this, "radar")
        refreshLocationIfGpsThenRun {
            Prefs.getLatLon(this)?.let { (lat, lon) -> homeLatLng = LatLng(lat, lon) }
            val map = mapLibreMap
            if (map == null) {
                loadTiles()
                return@refreshLocationIfGpsThenRun
            }
            recenter(map)
            // Rebuild the frame layers so the offsets re-resolve against
            // the current time - refresh is the only thing that touches
            // the base map's camera; the frame rebuild itself only adds/
            // removes the radar layers, not the base map style.
            map.style?.let { style ->
                animationJob?.cancel()
                removeAllFrameLayers(style)
                addAllFrameLayers(style)
            }
            status.text = "Refreshing radar..."
        }
    }

    private fun loadTiles() {
        status.text = "Loading radar..."
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.setAllGesturesEnabled(false) // key-driven, no touchscreen anyway

            map.setStyle(Style.Builder().fromUri(BASE_STYLE_URL)) { style ->
                // Set the camera AFTER the style finishes loading, not before -
                // the base style has its own default view baked in, and applying
                // it would silently override an earlier camera assignment,
                // landing you on a zoomed-out whole-world view.
                recenter(map)
                addAllFrameLayers(style)
            }
        }
    }

    /** Adds one raster source+layer per frame offset, stacked, all above the base map. */
    private fun addAllFrameLayers(style: Style) {
        isPlaying = false
        try {
            for ((i, offset) in FRAME_OFFSETS.withIndex()) {
                val sourceId = "radar-source-$i"
                val layerId = "radar-layer-$i"
                val tileSet = TileSet("2.1.0", tileUrlForOffset(offset))
                style.addSource(RasterSource(sourceId, tileSet, 256))
                val layer = RasterLayer(layerId, sourceId)
                style.addLayer(layer)
                layer.setProperties(
                    PropertyFactory.rasterOpacity(if (i == FRAME_OFFSETS.lastIndex) 1f else 0f)
                )
                frameSourceIds.add(sourceId)
                frameLayerIds.add(layerId)
            }
            currentFrameIndex = FRAME_OFFSETS.lastIndex
            status.text = "OK=Play/Stop"
            updateSlider() // start stopped on "Now" - animation begins only once OK is pressed
        } catch (e: Exception) {
            status.text = "Couldn't load radar tiles: ${e.message ?: "network error"}"
        }
    }

    private fun removeAllFrameLayers(style: Style) {
        for (layerId in frameLayerIds) style.removeLayer(layerId)
        for (sourceId in frameSourceIds) style.removeSource(sourceId)
        frameLayerIds.clear()
        frameSourceIds.clear()
    }

    private fun tileUrlForOffset(offset: String): String {
        val suffix = if (offset.isEmpty()) "900913" else "900913-$offset"
        return "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-$suffix/{z}/{x}/{y}.png"
    }

    /** Only touches raster-opacity on the frame layers - never the base map. */
    private fun showFrame(index: Int) {
        val style = mapLibreMap?.style ?: return
        for (i in frameLayerIds.indices) {
            val layer = style.getLayer(frameLayerIds[i]) as? RasterLayer ?: continue
            layer.setProperties(PropertyFactory.rasterOpacity(if (i == index) 1f else 0f))
        }
        updateSlider()
    }

    private fun setPlaying(playing: Boolean) {
        isPlaying = playing
        animationJob?.cancel()
        if (playing && frameLayerIds.isNotEmpty()) {
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
        if (frameLayerIds.isEmpty()) return
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
        if (frameLayerIds.isEmpty()) return
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

    private fun recenter(map: MapLibreMap) {
        val target = homeLatLng ?: return
        map.cameraPosition = CameraPosition.Builder().target(target).zoom(DEFAULT_ZOOM).build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercepted ahead of the normal view-focus dispatch (same trick
        // FlipBaseActivity uses for LEFT/RIGHT) - MapLibre's MapView can end
        // up holding focus regardless of the focusable="false" XML attrs, in
        // which case a focused view's own default click handling for
        // DPAD_CENTER/ENTER swallows the key before it ever reaches
        // onKeyDown, and OK silently does nothing. Handling it here, before
        // super.dispatchKeyEvent runs the view hierarchy, also takes it over
        // from FlipBaseActivity.onKeyDown's app-wide "jump to Daily".
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            togglePlayback()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val map = mapLibreMap ?: return super.onKeyDown(keyCode, event)
        val panPx = (mapView.width * PAN_FRACTION).toFloat()

        when (keyCode) {
            // Confirmed backward on-device from scrollBy's own documented
            // mapping, so signs are flipped here from what the docs suggest.
            // D-pad LEFT/RIGHT are excluded from the app-wide screen-shift
            // in FlipBaseActivity while on this screen (see its
            // dispatchKeyEvent), so they land here instead and pan.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                map.scrollBy(panPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                map.scrollBy(-panPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                map.scrollBy(0f, panPx)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                map.scrollBy(0f, -panPx)
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                map.moveCamera(CameraUpdateFactory.zoomBy(-ZOOM_STEP))
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                map.moveCamera(CameraUpdateFactory.zoomBy(ZOOM_STEP))
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                homeLatLng?.let {
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(it).zoom(DEFAULT_ZOOM).build()
                    ))
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        jump(DailyForecastActivity::class.java)
    }

    // --- MapView lifecycle forwarding (required by MapLibre) ---
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (isPlaying) setPlaying(true) // restart the loop if it was cancelled by onPause
    }
    override fun onPause() {
        mapView.onPause()
        animationJob?.cancel() // don't keep ticking while off-screen
        super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
