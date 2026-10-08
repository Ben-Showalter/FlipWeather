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
        const val MAX_ZOOM = 10
        private const val MAX_LAT = 85.0511
        private const val RETRY_FAILED_MS = 30_000L

        // Mapbox's own styling when a token is configured (local.properties
        // MAPBOX_TOKEN, see app/build.gradle), else CARTO's free dark map.
        private val BASE_URL: String
        private val ATTRIBUTION: String

        init {
            val token = BuildConfig.MAPBOX_TOKEN
            if (token.isNotBlank()) {
                BASE_URL = "https://api.mapbox.com/styles/v1/mapbox/dark-v11/tiles/256/{z}/{x}/{y}?access_token=$token"
                ATTRIBUTION = "© Mapbox © OpenStreetMap · Radar: NWS/IEM"
            } else {
                BASE_URL = "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
                ATTRIBUTION = "© CARTO © OpenStreetMap · Radar: NWS/IEM"
            }
        }

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

    var zoom = 7
        private set
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
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A1A") }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0A08E")
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
        for (key in cache.snapshot().keys) {
            if (!key.startsWith(BASE_URL.substringBefore("{z}"))) cache.remove(key)
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
        canvas.drawColor(Color.parseColor("#1A1410"))
        if (width == 0 || height == 0) return

        val z = zoom
        val n = 1 shl z
        val left = lonToWorldX(centerLon, z) - width / 2.0
        val top = latToWorldY(centerLat, z) - height / 2.0
        val firstX = Math.floor(left / TILE).toInt()
        val lastX = Math.floor((left + width) / TILE).toInt()
        val firstY = Math.floor(top / TILE).toInt().coerceAtLeast(0)
        val lastY = Math.floor((top + height) / TILE).toInt().coerceAtMost(n - 1)

        val frames = radarUrlTemplates
        val current = frames.getOrNull(frameIndex)

        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val wx = ((tx % n) + n) % n // wrap around the antimeridian
                val dl = (tx * TILE - left).toFloat()
                val dt = (ty * TILE - top).toFloat()
                dstRect.set(dl, dt, dl + TILE, dt + TILE)

                drawTile(canvas, BASE_URL, z, wx, ty, tilePaint)
                if (current != null) drawTile(canvas, current, z, wx, ty, radarPaint)
            }
        }

        // Prefetch the other frames for this view after the visible ones are queued.
        for (template in frames) {
            if (template == current) continue
            for (ty in firstY..lastY) for (tx in firstX..lastX) {
                request(urlFor(template, z, ((tx % n) + n) % n, ty))
            }
        }

        drawMarker(canvas, left, top, z)
        canvas.drawText(ATTRIBUTION, 4f, height - 4f, attributionPaint)
    }

    /** Draws one tile into [dstRect]; if it isn't loaded yet, falls back to a scaled-up parent tile. */
    private fun drawTile(canvas: Canvas, template: String, z: Int, x: Int, y: Int, paint: Paint) {
        val url = urlFor(template, z, x, y)
        val bmp = cache.get(url)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, dstRect, paint)
            return
        }
        request(url)
        if (z > MIN_ZOOM) {
            val parent = cache.get(urlFor(template, z - 1, x / 2, y / 2)) ?: return
            val half = TILE / 2
            val sx = (x % 2) * half
            val sy = (y % 2) * half
            srcRect.set(sx, sy, sx + half, sy + half)
            canvas.drawBitmap(parent, srcRect, dstRect, paint)
        }
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
