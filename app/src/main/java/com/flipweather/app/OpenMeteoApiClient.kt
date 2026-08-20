package com.flipweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** org.json's array getters throw on a JSON `null` instead of just returning one - these don't. */
private fun JSONArray.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
private fun JSONArray.roundedOrNull(i: Int): Int? = if (isNull(i)) null else Math.round(getDouble(i)).toInt()

/**
 * Open-Meteo (open-meteo.com) - free, no API key, up to 16 days out.
 * Used only for the Daily/Hourly screens, since NWS itself tops out at
 * 7 days / 14 periods. Current conditions still come from NWS.
 */
object OpenMeteoApiClient {

    /** Free geocoding - turns a typed town name into candidate lat/lon matches. */
    suspend fun searchTowns(query: String): List<GeocodeResult> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=en&format=json"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("Geocoding request failed ($code)")

            val json = JSONObject(text)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            val list = ArrayList<GeocodeResult>()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                list.add(
                    GeocodeResult(
                        name = r.getString("name"),
                        admin1 = r.optString("admin1", ""),
                        country = r.optString("country", ""),
                        latitude = r.getDouble("latitude"),
                        longitude = r.getDouble("longitude")
                    )
                )
            }
            list
        }

    suspend fun getForecast(lat: Double, lon: Double, days: Int = 16): OpenMeteoForecast =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=%.4f&longitude=%.4f".format(lat, lon) +
                "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&hourly=temperature_2m,weathercode,precipitation_probability" +
                "&temperature_unit=fahrenheit&windspeed_unit=mph&timezone=auto" +
                "&forecast_days=$days"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("Open-Meteo request failed ($code)")

            val json = JSONObject(text)

            val daily = json.getJSONObject("daily")
            val dailyTimes = daily.getJSONArray("time")
            val dailyCodes = daily.getJSONArray("weathercode")
            val dailyMax = daily.getJSONArray("temperature_2m_max")
            val dailyMin = daily.getJSONArray("temperature_2m_min")
            val dailyPrecip = daily.optJSONArray("precipitation_probability_max")
            val dailyList = ArrayList<DailyForecastEntry>()
            for (i in 0 until dailyTimes.length()) {
                dailyList.add(
                    DailyForecastEntry(
                        date = dailyTimes.getString(i),
                        // Open-Meteo can leave the last day or two of a 16-day
                        // request null for some fields (edge of model coverage) -
                        // fall back to a neutral "unknown" code rather than crash.
                        weatherCode = dailyCodes.intOrNull(i) ?: -1,
                        tempMaxF = dailyMax.roundedOrNull(i),
                        tempMinF = dailyMin.roundedOrNull(i),
                        precipProbPercent = dailyPrecip?.optInt(i, -1)?.takeIf { it >= 0 }
                    )
                )
            }

            val hourly = json.getJSONObject("hourly")
            val hourlyTimes = hourly.getJSONArray("time")
            val hourlyCodes = hourly.getJSONArray("weathercode")
            val hourlyTemps = hourly.getJSONArray("temperature_2m")
            val hourlyPrecip = hourly.optJSONArray("precipitation_probability")
            val hourlyList = ArrayList<HourlyForecastEntry>()
            for (i in 0 until hourlyTimes.length()) {
                hourlyList.add(
                    HourlyForecastEntry(
                        dateTime = hourlyTimes.getString(i), // e.g. "2026-08-20T14:00"
                        weatherCode = hourlyCodes.intOrNull(i) ?: -1,
                        tempF = hourlyTemps.roundedOrNull(i),
                        precipProbPercent = hourlyPrecip?.optInt(i, -1)?.takeIf { it >= 0 }
                    )
                )
            }

            OpenMeteoForecast(dailyList, hourlyList)
        }
}

data class GeocodeResult(
    val name: String,
    val admin1: String,
    val country: String,
    val latitude: Double,
    val longitude: Double
) {
    val label: String get() = listOf(name, admin1, country).filter { it.isNotBlank() }.joinToString(", ")
}

data class OpenMeteoForecast(
    val daily: List<DailyForecastEntry>,
    val hourly: List<HourlyForecastEntry>
)

data class DailyForecastEntry(
    val date: String,          // "2026-08-20"
    val weatherCode: Int,
    val tempMaxF: Int?,
    val tempMinF: Int?,
    val precipProbPercent: Int?
)

data class HourlyForecastEntry(
    val dateTime: String,      // "2026-08-20T14:00"
    val weatherCode: Int,
    val tempF: Int?,
    val precipProbPercent: Int?
) {
    val date: String get() = dateTime.substringBefore("T")
    val hour: String get() = dateTime.substringAfter("T")
}

/**
 * Tiny in-memory cache so drilling from Daily -> Hourly for a day doesn't
 * re-hit the network - cleared whenever the saved location changes.
 */
object OpenMeteoCache {
    var forecast: OpenMeteoForecast? = null
        private set

    fun set(f: OpenMeteoForecast) { forecast = f }
    fun clear() { forecast = null }
}
