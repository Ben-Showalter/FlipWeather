package com.flipweather.app

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class CurrentActivity : FlipBaseActivity() {

    private lateinit var locationLabel: TextView
    private lateinit var currentIcon: ImageView
    private lateinit var currentTemp: TextView
    private lateinit var currentDesc: TextView
    private lateinit var currentDetails: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_current)

        locationLabel = findViewById(R.id.locationLabel)
        currentIcon = findViewById(R.id.currentIcon)
        currentTemp = findViewById(R.id.currentTemp)
        currentDesc = findViewById(R.id.currentDesc)
        currentDetails = findViewById(R.id.currentDetails)
        statusText = findViewById(R.id.statusText)
    }

    override fun onResume() {
        super.onResume()
        // Reopening this screen shows whatever was last downloaded,
        // instantly - no network call just from navigating back to it.
        // Left softkey (onRefreshKey) is the only thing that re-fetches.
        if (!showFromCache()) {
            // Nothing cached yet (first launch, or location just changed) -
            // there's nothing else to show, so fetch once automatically.
            fetchFresh()
        }
    }

    override fun onRefreshKey() {
        if (!RefreshThrottle.canRefresh(this, "current", RefreshThrottle.CURRENT_MIN_MS)) {
            statusText.text = RefreshThrottle.waitMessage(this, "current", RefreshThrottle.CURRENT_MIN_MS)
            return
        }
        RefreshThrottle.markRefreshed(this, "current")
        statusText.text = "Refreshing..."
        refreshLocationIfGpsThenRun { fetchFresh() }
    }

    /** Renders the last cached fetch, if any. Returns false if there's nothing cached. */
    private fun showFromCache(): Boolean {
        if (!Prefs.hasLocation(this)) {
            locationLabel.text = "No location set"
            statusText.text = "Press Right (Options) to set a location"
            currentTemp.text = "--°"
            currentDesc.text = ""
            currentDetails.text = ""
            currentIcon.setImageDrawable(null)
            return true // nothing to fetch without a location either
        }

        locationLabel.text = Prefs.getLocationLabel(this) ?: "Current Location"

        val cached = Prefs.getCachedCurrentJson(this) ?: return false
        try {
            val json = JSONObject(cached)
            currentTemp.text = if (json.has("temp")) "${json.getInt("temp")}°F" else "--°"
            currentDesc.text = json.optString("desc", "")
            currentDetails.text = json.optString("details", "")
            statusText.text = "${json.optString("station", "")} · as of ${json.optString("time", "")} (cached)"

            val iconUrl = json.optString("iconUrl", "")
            if (iconUrl.isNotBlank()) {
                loadIconInto(currentIcon, iconUrl, fallbackText = json.optString("desc", ""))
            } else {
                currentIcon.setImageResource(WeatherIcons.drawableForShortForecast(json.optString("desc", "")))
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun fetchFresh() {
        if (!Prefs.hasLocation(this)) return
        val (lat, lon) = Prefs.getLatLon(this)!!
        statusText.text = "Loading current conditions..."

        lifecycleScope.launch {
            try {
                val grid = Prefs.getGridpoint(this@CurrentActivity)
                    ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@CurrentActivity, it) }

                val stationId = Prefs.getObsStationId(this@CurrentActivity)
                    ?: NwsApiClient.getNearestStationId(grid.observationStationsUrl).also {
                        Prefs.setObsStationId(this@CurrentActivity, it)
                    }

                val obs = NwsApiClient.getLatestObservation(stationId)
                // The station name/location is the "exact" place this reading
                // is actually from - same as the location forecast.weather.gov
                // prints above its Current Conditions panel.
                val timeLabel = formatObsTime(obs.timestamp)
                statusText.text = "${obs.stationName} · as of $timeLabel"

                currentTemp.text = obs.temperatureF?.let { "$it°F" } ?: "--°"
                currentDesc.text = obs.textDescription

                val details = StringBuilder()
                if (obs.feelsLikeF != null && obs.feelsLikeF != obs.temperatureF) {
                    details.append("Feels like ${obs.feelsLikeF}°F\n")
                }
                val wind = StringBuilder()
                obs.windDirection?.let { wind.append("$it ") }
                if (obs.windSpeedMph != null) wind.append("${obs.windSpeedMph} mph") else wind.append("Calm")
                obs.windGustMph?.let { wind.append(", gusts $it mph") }
                details.append("Wind $wind\n")
                obs.humidityPercent?.let { details.append("Humidity $it%\n") }
                obs.dewpointF?.let { details.append("Dewpoint $it°F\n") }
                obs.pressureInHg?.let { details.append("Pressure ${"%.2f".format(it)} in\n") }
                obs.visibilityMi?.let { details.append("Visibility ${"%.1f".format(it)} mi") }
                val detailsText = details.toString().trimEnd('\n')
                currentDetails.text = detailsText

                if (obs.iconUrl.isNotBlank()) {
                    loadIconInto(currentIcon, obs.iconUrl, fallbackText = obs.textDescription)
                } else {
                    currentIcon.setImageResource(WeatherIcons.drawableForShortForecast(obs.textDescription))
                }

                // Cache this fetch so reopening the screen shows it instantly
                // next time, without a network call.
                val cacheJson = JSONObject().apply {
                    put("temp", obs.temperatureF ?: JSONObject.NULL)
                    put("desc", obs.textDescription)
                    put("details", detailsText)
                    put("station", obs.stationName)
                    put("time", timeLabel)
                    put("iconUrl", obs.iconUrl)
                }
                Prefs.setCachedCurrentJson(this@CurrentActivity, cacheJson.toString())
            } catch (e: Exception) {
                statusText.text = "Couldn't load conditions: ${e.message ?: "network error"}"
            }
        }
    }

    /** Loads the NWS-hosted icon image; falls back to our own icon set if that fails or is missing. */
    private fun loadIconInto(view: ImageView, url: String, fallbackText: String) {
        lifecycleScope.launch {
            try {
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", NwsApiClient.USER_AGENT)
                    conn.connect()
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                } else {
                    view.setImageResource(WeatherIcons.drawableForShortForecast(fallbackText))
                }
            } catch (e: Exception) {
                // NWS doesn't have a rendered icon for every condition (e.g.
                // some fog/haze variants) - rather than leave this blank,
                // fall back to our own bundled icon set.
                view.setImageResource(WeatherIcons.drawableForShortForecast(fallbackText))
            }
        }
    }

    /** NWS timestamps are ISO-8601 with a numeric offset, e.g. "2026-08-17T13:40:00+00:00". */
    private fun formatObsTime(iso: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)
        SimpleDateFormat("h:mm a", Locale.US).format(parsed!!)
    } catch (e: Exception) {
        iso
    }
}
