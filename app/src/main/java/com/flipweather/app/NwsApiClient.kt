package com.flipweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * All calls are to api.weather.gov (NOAA/NWS), no API key required.
 *
 * NWS asks that automated clients identify themselves with a contact
 * email in the User-Agent string - edit USER_AGENT below before you
 * build/install this, per https://www.weather.gov/documentation/services-web-api
 */
object NwsApiClient {

    // TODO: replace with a real contact email before building - NWS requests this.
    const val USER_AGENT = "FlipWeather/1.0 (contact: your-email@example.com)"

    private fun get(urlStr: String, accept: String = "application/geo+json"): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", accept)
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.connect()
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw java.io.IOException("NWS request failed ($code): $urlStr")
        }
        return text
    }

    /**
     * Step 1 of the NWS flow: turn a lat/lon into the forecast office,
     * gridpoint (x,y), radar station id, and the forecast URL to call next.
     */
    suspend fun lookupGridpoint(lat: Double, lon: Double): GridpointInfo =
        withContext(Dispatchers.IO) {
            val url = "https://api.weather.gov/points/%.4f,%.4f".format(lat, lon)
            val json = JSONObject(get(url))
            val props = json.getJSONObject("properties")
            GridpointInfo(
                office = props.getString("gridId"),
                gridX = props.getInt("gridX"),
                gridY = props.getInt("gridY"),
                radarStation = props.optString("radarStation", ""),
                forecastUrl = props.getString("forecast"),
                hourlyForecastUrl = props.getString("forecastHourly"),
                observationStationsUrl = props.getString("observationStations")
            )
        }

    /**
     * Resolves the nearest observation station id (e.g. "KEAX") for a
     * gridpoint's observationStations collection URL - the first station
     * returned is the closest to the original lat/lon.
     */
    suspend fun getNearestStationId(observationStationsUrl: String): String =
        withContext(Dispatchers.IO) {
            val json = JSONObject(get(observationStationsUrl))
            val features = json.getJSONArray("features")
            if (features.length() == 0) throw java.io.IOException("No observation stations found nearby")
            features.getJSONObject(0).getJSONObject("properties").getString("stationIdentifier")
        }

    /**
     * The latest observed conditions from a station - this is what
     * "current weather" actually means on NWS (nearest ground station
     * reading), not a modeled current-conditions estimate. Mirrors what
     * forecast.weather.gov's "Current Conditions" panel shows: the
     * station's own name/location plus feels-like, dewpoint, pressure,
     * and visibility alongside the basics.
     */
    suspend fun getLatestObservation(stationId: String): CurrentObservation =
        withContext(Dispatchers.IO) {
            val url = "https://api.weather.gov/stations/$stationId/observations/latest"
            val props = JSONObject(get(url)).getJSONObject("properties")

            fun num(key: String): Double? =
                props.optJSONObject(key)?.let { if (it.isNull("value")) null else it.getDouble("value") }

            val tempC = num("temperature")
            val dewpointC = num("dewpoint")
            val windChillC = num("windChill")
            val heatIndexC = num("heatIndex")
            val windKmh = num("windSpeed")
            val windGustKmh = num("windGust")
            val windDirectionDeg = num("windDirection")
            val humidity = num("relativeHumidity")
            val pressurePa = num("barometricPressure")
            val visibilityM = num("visibility")

            val tempF = tempC?.let { cToF(it) }
            // "Feels like": NWS publishes windChill in cold conditions, heatIndex in hot
            // conditions, and leaves both null otherwise - fall back to actual temp then.
            val feelsLikeF = (windChillC ?: heatIndexC)?.let { cToF(it) } ?: tempF

            CurrentObservation(
                temperatureF = tempF,
                feelsLikeF = feelsLikeF,
                dewpointF = dewpointC?.let { cToF(it) },
                textDescription = props.optString("textDescription", ""),
                iconUrl = props.optString("icon", ""),
                windSpeedMph = windKmh?.let { kmhToMph(it) },
                windGustMph = windGustKmh?.let { kmhToMph(it) },
                windDirection = windDirectionDeg?.let { degreesToCompass(it) },
                humidityPercent = humidity?.let { Math.round(it).toInt() },
                pressureInHg = pressurePa?.let { it / 3386.389 },
                visibilityMi = visibilityM?.let { it / 1609.344 },
                timestamp = props.optString("timestamp", ""),
                stationId = stationId,
                // The observation payload already carries the station's own
                // name/location (e.g. "Washington/Reagan National Airport, DC") -
                // that's the "exact location" the reading is really from.
                stationName = props.optString("stationName", stationId)
            )
        }

    /**
     * Step 2: the 7-day / 14-period (day + night) forecast for a gridpoint.
     * NWS does not publish per-day forecasts beyond ~7 days out.
     *
     * Also reused for the *hourly* forecast - the /gridpoints/.../forecast/hourly
     * endpoint returns periods in this exact same shape, just one hour wide
     * each instead of one day/night half wide.
     */
    suspend fun getForecastPeriods(forecastUrl: String): List<ForecastPeriod> =
        withContext(Dispatchers.IO) {
            val json = JSONObject(get(forecastUrl))
            val periods = json.getJSONObject("properties").getJSONArray("periods")
            val result = ArrayList<ForecastPeriod>()
            for (i in 0 until periods.length()) {
                val p = periods.getJSONObject(i)
                val precipProb = p.optJSONObject("probabilityOfPrecipitation")
                    ?.let { if (it.isNull("value")) null else it.optInt("value") }
                result.add(
                    ForecastPeriod(
                        name = p.getString("name"),
                        startTime = p.getString("startTime"),
                        endTime = p.optString("endTime", ""),
                        temperature = p.getInt("temperature"),
                        temperatureUnit = p.getString("temperatureUnit"),
                        shortForecast = p.getString("shortForecast"),
                        icon = p.optString("icon", ""),
                        windSpeed = p.optString("windSpeed", ""),
                        windDirection = p.optString("windDirection", ""),
                        isDaytime = p.getBoolean("isDaytime"),
                        precipProbPercent = precipProb
                    )
                )
            }
            result
        }

    /**
     * Expected precipitation / snowfall / ice amounts from the raw
     * gridpoint forecast. NWS publishes these mostly as 6-hour totals
     * (validTime like "2026-10-07T12:00:00+00:00/PT6H"), and usually
     * only ~3 days out - each block is spread evenly over its hours so
     * it can be summed over any day/night period, see [PrecipTimeline].
     */
    suspend fun getGridpointPrecip(grid: GridpointInfo): PrecipTimeline =
        withContext(Dispatchers.IO) {
            val url = "https://api.weather.gov/gridpoints/${grid.office}/${grid.gridX},${grid.gridY}"
            val props = JSONObject(get(url)).getJSONObject("properties")
            PrecipTimeline(
                precipMm = hourlySeriesMm(props, "quantitativePrecipitation"),
                snowMm = hourlySeriesMm(props, "snowfallAmount"),
                iceMm = hourlySeriesMm(props, "iceAccumulation")
            )
        }

    /** Spreads one gridpoint layer's values evenly over each hour they cover, keyed by hour-start epoch ms. */
    private fun hourlySeriesMm(props: JSONObject, key: String): Map<Long, Double> {
        val layer = props.optJSONObject(key) ?: return emptyMap()
        // Amounts are normally "wmoUnit:mm"; tolerate meters just in case.
        val toMm = if (layer.optString("uom", "").endsWith(":m")) 1000.0 else 1.0
        val values = layer.optJSONArray("values") ?: return emptyMap()
        val result = HashMap<Long, Double>()
        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            if (v.isNull("value")) continue
            val (startIso, duration) = v.getString("validTime").split("/").let {
                it[0] to it.getOrElse(1) { "PT1H" }
            }
            val startMs = isoToEpochMs(startIso) ?: continue
            val hours = isoDurationHours(duration)
            val perHour = v.getDouble("value") * toMm / hours
            for (h in 0 until hours) {
                val hourMs = startMs + h * 3_600_000L
                result[hourMs] = (result[hourMs] ?: 0.0) + perHour
            }
        }
        return result
    }

    /** "PT6H" -> 6, "P1DT6H" -> 30, "P1D" -> 24; at least 1. */
    private fun isoDurationHours(duration: String): Int {
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?""").matchEntire(duration) ?: return 1
        val days = m.groupValues[1].toIntOrNull() ?: 0
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        return maxOf(1, days * 24 + hours)
    }

    /**
     * The latest Area Forecast Discussion - the forecasters' own write-up
     * of their reasoning - from the local forecast office (e.g. "EAX").
     */
    suspend fun getLatestDiscussion(office: String): ForecastDiscussion =
        withContext(Dispatchers.IO) {
            val list = JSONObject(get("https://api.weather.gov/products/types/AFD/locations/$office", "application/ld+json"))
            val graph = list.optJSONArray("@graph")
            if (graph == null || graph.length() == 0) throw java.io.IOException("No discussion published for $office")
            val id = graph.getJSONObject(0).getString("id")
            val product = JSONObject(get("https://api.weather.gov/products/$id", "application/ld+json"))
            ForecastDiscussion(
                office = office,
                issuanceTime = product.optString("issuanceTime", ""),
                text = product.optString("productText", "")
            )
        }

    private fun cToF(c: Double): Int = Math.round(c * 9.0 / 5.0 + 32.0).toInt()
    private fun kmhToMph(kmh: Double): Int = Math.round(kmh / 1.609).toInt()

    private fun degreesToCompass(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = (Math.floor(deg / 22.5 + 0.5).toInt()).mod(16)
        return dirs[idx]
    }
}

data class GridpointInfo(
    val office: String,
    val gridX: Int,
    val gridY: Int,
    val radarStation: String,
    val forecastUrl: String,
    val hourlyForecastUrl: String,
    val observationStationsUrl: String
)

data class CurrentObservation(
    val temperatureF: Int?,
    val feelsLikeF: Int?,
    val dewpointF: Int?,
    val textDescription: String,
    val iconUrl: String,
    val windSpeedMph: Int?,
    val windGustMph: Int?,
    val windDirection: String?,
    val humidityPercent: Int?,
    val pressureInHg: Double?,
    val visibilityMi: Double?,
    val timestamp: String,
    val stationId: String,
    val stationName: String
)

data class ForecastPeriod(
    val name: String,
    val startTime: String,
    val endTime: String,
    val temperature: Int,
    val temperatureUnit: String,
    val shortForecast: String,
    val icon: String,
    val windSpeed: String,
    val windDirection: String,
    val isDaytime: Boolean,
    val precipProbPercent: Int?
) {
    /** "2026-08-20T14:00:00-05:00" -> "2026-08-20" */
    val date: String get() = startTime.substringBefore("T")
}

data class ForecastDiscussion(
    val office: String,
    val issuanceTime: String,
    val text: String
)

/** Precip amounts in one period - inches; see [PrecipTimeline.sumInches]. */
data class PrecipAmounts(val precipIn: Double, val snowIn: Double, val iceIn: Double) {
    val isZero: Boolean get() = precipIn < 0.005 && snowIn < 0.05 && iceIn < 0.005
}

/** Gridpoint precip/snow/ice amounts spread per hour (mm), keyed by hour-start epoch ms. */
class PrecipTimeline(
    private val precipMm: Map<Long, Double>,
    private val snowMm: Map<Long, Double>,
    private val iceMm: Map<Long, Double>
) {
    /** Totals over [startMs, endMs); null if NWS has no amounts for any of those hours. */
    fun sumInches(startMs: Long, endMs: Long): PrecipAmounts? {
        var covered = false
        fun sum(series: Map<Long, Double>): Double {
            var total = 0.0
            for ((hourMs, mm) in series) {
                if (hourMs in startMs until endMs) {
                    total += mm
                    covered = true
                }
            }
            return total / 25.4
        }
        val amounts = PrecipAmounts(sum(precipMm), sum(snowMm), sum(iceMm))
        return if (covered) amounts else null
    }
}

/** NWS ISO-8601 timestamp with a numeric offset ("2026-08-17T13:40:00-05:00") -> epoch ms. */
fun isoToEpochMs(iso: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)?.time
} catch (e: Exception) {
    null
}
