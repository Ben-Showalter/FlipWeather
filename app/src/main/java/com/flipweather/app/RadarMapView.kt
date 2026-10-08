package com.flipweather.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A plain-Canvas slippy map: base-map tiles with one radar frame's
 * tiles drawn on top, all as ordinary 256px web-mercator XYZ PNGs.
 *
 * Deliberately no OpenGL - MapLibre's GL renderer crashed natively
 * (SIGBUS in its render thread) on the Kyocera E4610's older Adreno
 * driver, and a GL crash can't be caught from Kotlin. Drawing bitmaps
 * with Canvas works on any device and is plenty for a key-driven map.
 *
 * Every animation frame's radar tiles are fetched up front (current
 * frame first), so switching frames is just a redraw from cache.
 */
class RadarMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TILE = 256
        const val MIN_ZOOM = 3
        // Base maps go this deep (where small roads and trails show up);
        // past RADAR_MAX_ZOOM the radar is hidden rather than stretched.
        const val MAX_ZOOM = 16
        const val RADAR_MAX_ZOOM = 10
        private const val MAX_LAT = 85.0511
        private const val RETRY_FAILED_MS = 30_000L

        private const val RADAR_HOST = "https://mesonet.agron.iastate.edu/"

        // Esri tiles need no API key (CARTO's came back "API key required"
        // on-device). Note Esri's tile URLs are {z}/{y}/{x}, not {z}/{x}/{y}.
        private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services"
        private const val RADAR_CREDIT = "Radar: NWS/IEM"

        // Mapbox's own styles instead, when a token is configured
        // (local.properties MAPBOX_TOKEN, see app/build.gradle).
        private val MAPBOX_TOKEN = BuildConfig.MAPBOX_TOKEN
        private fun mapbox(style: String) =
            "https://api.mapbox.com/styles/v1/mapbox/$style/tiles/256/{z}/{x}/{y}?access_token=$MAPBOX_TOKEN"

        /**
         * Esri Dark Gray keeps roads nearly invisible, so its transparent
         * World Transportation overlay adds them, then the Dark Gray labels
         * go on top. Both overlays sit above the radar so roads and towns
         * stay readable through rain.
         */
        val DARK: MapStyle = if (MAPBOX_TOKEN.isNotBlank()) {
            MapStyle(mapbox("dark-v11"), emptyList(), 200, isLight = false, attribution = "© Mapbox © OpenStreetMap · $RADAR_CREDIT")
        } else {
            MapStyle(
                base = "$ESRI/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}",
                overlays = listOf(
                    "$ESRI/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}" to 180,
                    "$ESRI/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}" to 255
                ),
                radarAlpha = 200,
                isLight = false,
                attribution = "Powered by Esri · $RADAR_CREDIT"
            )
        }

        /** Topographic: every road, trails, parks and terrain - trails from about zoom 13. */
        val LIGHT: MapStyle = if (MAPBOX_TOKEN.isNotBlank()) {
            MapStyle(mapbox("outdoors-v12"), emptyList(), 170, isLight = true, attribution = "© Mapbox © OpenStreetMap · $RADAR_CREDIT")
        } else {
            MapStyle(
                base = "$ESRI/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
                overlays = emptyList(),
                // A bit lighter so the map's roads and labels show through.
                radarAlpha = 170,
                isLight = true,
                attribution = "Powered by Esri · $RADAR_CREDIT"
            )
        }

        fun styleFor(name: String): MapStyle = if (name == Prefs.RADAR_STYLE_LIGHT) LIGHT else DARK

        // --- Web-mercator math, in world pixels at zoom z (256 * 2^z wide) ---

        fun lonToWorldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)

        fun latToWorldY(lat: Double, z: Int): Double {
            val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * worldSize(z)
        }

        fun worldXToLon(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0

        fun worldYToLat(y: Double, z: Int): Double {
            val n = Math.PI - 2.0 * Math.PI * y / worldSize(z)
            return Math.toDegrees(Math.atan(Math.sinh(n)))
        }

        fun worldSize(z: Int): Double = TILE.toDouble() * (1 shl z)
    }

    /** IEM URL templates, oldest to newest - see RadarActivity.FRAME_OFFSETS. */
    var radarUrlTemplates: List<String> = emptyList()
        set(value) {
            field = value
            frameIndex = frameIndex.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            invalidate()
        }

    var frameIndex = 0
        set(value) {
            field = value
            invalidate()
        }

    var mapStyle: MapStyle = LIGHT
        set(value) {
            if (field === value) return
            field = value
            radarPaint.alpha = value.radarAlpha
            attributionPaint.color = Color.parseColor(if (value.isLight) "#333333" else "#B0A08E")
            invalidate()
        }

    var zoom = 7
        private set

    /** True when zoomed in past the radar's resolution - see RADAR_MAX_ZOOM. */
    val isRadarHidden: Boolean get() = zoom > RADAR_MAX_ZOOM

    /**
     * Called (posted, on the main thread) whenever everything needed for the
     * current view - base map, overlays and every radar frame - flips
     * between all-loaded and still-loading.
     */
    var onLoadStateChanged: ((fullyLoaded: Boolean) -> Unit)? = null
    private var lastFullyLoaded: Boolean? = null
    private var missingTiles = 0
    private var centerLat = 39.0
    private var centerLon = -95.0
    private var homeLat: Double? = null
    private var homeLon: Double? = null

    // Bitmaps are 256-384 KB each; a quarter of the heap holds a full
    // screen of base tiles plus all radar frames for it on a flip phone.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private var executor: ExecutorService = Executors.newFixedThreadPool(3)

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = LIGHT.radarAlpha }
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A1A") }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 9f * resources.displayMetrics.scaledDensity
    }
    private val srcRect = Rect()
    private val dstRect = RectF()

    fun setHome(lat: Double, lon: Double) {
        homeLat = lat
        homeLon = lon
    }

    /** Back to the saved location at [z]. */
    fun recenter(z: Int) {
        val lat = homeLat ?: return
        val lon = homeLon ?: return
        centerLat = lat
        centerLon = lon
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    /** Pans by a fraction of the view's width/height (positive = east / south). */
    fun panBy(dxFraction: Double, dyFraction: Double) {
        val x = lonToWorldX(centerLon, zoom) + dxFraction * width
        val y = latToWorldY(centerLat, zoom) + dyFraction * height
        val size = worldSize(zoom)
        centerLon = worldXToLon(((x % size) + size) % size, zoom)
        centerLat = worldYToLat(y.coerceIn(0.0, size), zoom).coerceIn(-MAX_LAT, MAX_LAT)
        invalidate()
    }

    fun zoomBy(delta: Int) {
        val z = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return
        zoom = z
        invalidate()
    }

    /** Drops cached radar tiles so the time offsets re-resolve against now; the base map stays. */
    fun reloadRadar() {
        lastFullyLoaded = null
        for (key in cache.snapshot().keys) {
            if (key.startsWith(RADAR_HOST)) cache.remove(key)
        }
        failedAt.clear()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (executor.isShutdown) executor = Executors.newFixedThreadPool(3)
    }

    override fun onDetachedFromWindow() {
        executor.shutdownNow()
        inFlight.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val style = mapStyle
        canvas.drawColor(Color.parseColor(if (style.isLight) "#E8E4DC" else "#1A1410"))
        if (width == 0 || height == 0) return

        val z = zoom
        val n = 1 shl z
        val left = lonToWorldX(centerLon, z) - width / 2.0
        val top = latToWorldY(centerLat, z) - height / 2.0
        val firstX = Math.floor(left / TILE).toInt()
        val lastX = Math.floor((left + width) / TILE).toInt()
        val firstY = Math.floor(top / TILE).toInt().coerceAtLeast(0)
        val lastY = Math.floor((top + height) / TILE).toInt().coerceAtMost(n - 1)

        val frames = if (isRadarHidden) emptyList() else radarUrlTemplates
        val current = frames.getOrNull(frameIndex)
        missingTiles = 0

        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val wx = ((tx % n) + n) % n // wrap around the antimeridian
                val dl = (tx * TILE - left).toFloat()
                val dt = (ty * TILE - top).toFloat()
                dstRect.set(dl, dt, dl + TILE, dt + TILE)

                drawTile(canvas, style.base, z, wx, ty, tilePaint, MAX_ZOOM)
                if (current != null) drawTile(canvas, current, z, wx, ty, radarPaint, RADAR_MAX_ZOOM)
                for ((template, alpha) in style.overlays) {
                    overlayPaint.alpha = alpha
                    drawTile(canvas, template, z, wx, ty, overlayPaint, MAX_ZOOM)
                }
            }
        }

        // Prefetch the other frames for this view after the visible ones are queued.
        for (template in frames) {
            if (template == current) continue
            for (ty in firstY..lastY) for (tx in firstX..lastX) {
                val url = urlFor(template, z, ((tx % n) + n) % n, ty)
                if (cache.get(url) == null) {
                    missingTiles++
                    request(url)
                }
            }
        }

        val fullyLoaded = missingTiles == 0
        if (fullyLoaded != lastFullyLoaded) {
            lastFullyLoaded = fullyLoaded
            onLoadStateChanged?.let { cb -> post { cb(fullyLoaded) } }
        }

        drawMarker(canvas, left, top, z)
        canvas.drawText(style.attribution, 4f, height - 4f, attributionPaint)
    }

    /**
     * Draws tile (z, x, y) of [template] into [dstRect]. Past [maxNativeZoom]
     * the covering tile at that zoom is stretched instead. While a tile is
     * still loading, the next coarser tile stands in, stretched, if cached.
     */
    private fun drawTile(canvas: Canvas, template: String, z: Int, x: Int, y: Int, paint: Paint, maxNativeZoom: Int) {
        val nz = minOf(z, maxNativeZoom)
        if (drawFromZoom(canvas, template, z, x, y, nz, paint, fetch = true)) return
        missingTiles++
        if (nz > MIN_ZOOM) drawFromZoom(canvas, template, z, x, y, nz - 1, paint, fetch = false)
    }

    /** Draws the part of the zoom-[az] ancestor tile covering (z, x, y); false if it isn't cached. */
    private fun drawFromZoom(
        canvas: Canvas, template: String, z: Int, x: Int, y: Int, az: Int, paint: Paint, fetch: Boolean
    ): Boolean {
        val dz = z - az
        val ax = x shr dz
        val ay = y shr dz
        val url = urlFor(template, az, ax, ay)
        val bmp = cache.get(url)
        if (bmp == null) {
            if (fetch) request(url)
            return false
        }
        if (dz == 0) {
            canvas.drawBitmap(bmp, null, dstRect, paint)
        } else {
            val sub = (bmp.width shr dz).coerceAtLeast(1)
            val sx = (x - (ax shl dz)) * sub
            val sy = (y - (ay shl dz)) * sub
            srcRect.set(sx, sy, sx + sub, sy + sub)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        }
        return true
    }

    private fun drawMarker(canvas: Canvas, left: Double, top: Double, z: Int) {
        val lat = homeLat ?: return
        val lon = homeLon ?: return
        val size = worldSize(z)
        var px = lonToWorldX(lon, z) - left
        // Pick the copy of the world nearest the view so the marker survives wrapping.
        if (px < -size / 2) px += size else if (px > size / 2 + width) px -= size
        val py = latToWorldY(lat, z) - top
        val r = 4f * resources.displayMetrics.density
        canvas.drawCircle(px.toFloat(), py.toFloat(), r, markerFill)
        canvas.drawCircle(px.toFloat(), py.toFloat(), r, markerRing)
    }

    private fun urlFor(template: String, z: Int, x: Int, y: Int) =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    private fun request(url: String) {
        if (cache.get(url) != null || executor.isShutdown) return
        val failed = failedAt[url]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_FAILED_MS) return
        if (!inFlight.add(url)) return
        try {
            executor.execute {
                try {
                    val bmp = download(url)
                    if (bmp != null) {
                        cache.put(url, bmp)
                        failedAt.remove(url)
                        postInvalidate()
                    } else {
                        failedAt[url] = SystemClock.elapsedRealtime()
                    }
                } finally {
                    inFlight.remove(url)
                }
            }
        } catch (e: Exception) {
            inFlight.remove(url) // executor shut down between the check and execute
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", NwsApiClient.USER_AGENT)
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode in 200..299) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

/** One radar map look: a base layer, then the radar, then [overlays] (template to alpha) above it. */
class MapStyle(
    val base: String,
    val overlays: List<Pair<String, Int>>,
    val radarAlpha: Int,
    val isLight: Boolean,
    val attribution: String
)
