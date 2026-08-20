package com.flipweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    private fun get(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/geo+json")
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
