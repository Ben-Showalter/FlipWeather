package com.flipweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LibreWXR (https://librewxr.net, https://github.com/JoshuaKimsey/LibreWXR)
 * is a free, open-source, self-hostable, drop-in replacement for the
 * Rain Viewer v2 tile API - real radar composites (not just US NEXRAD)
 * plus regional model data, no API key.
 *
 * BASE_HOST below points at the project's own public instance to get
 * you started. For anything you want to rely on long-term, self-host
 * your own LibreWXR server (see the GitHub repo) and point BASE_HOST
 * at that instead - a public instance run by someone else has no
 * uptime guarantee for a third-party app.
 */
object LibreWxrClient {

    const val BASE_HOST = "https://api.librewxr.net"

    /** The Rain Viewer v2 shape: a JSON index of available frames, fetched fresh each time. */
    suspend fun getLatestRadarFrame(baseHost: String = BASE_HOST): RadarFrame =
        withContext(Dispatchers.IO) {
            val conn = URL("$baseHost/public/weather-maps.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("LibreWXR request failed ($code)")

            val json = JSONObject(text)
            val host = json.optString("host", baseHost)
            val pastFrames = json.getJSONObject("radar").getJSONArray("past")
            if (pastFrames.length() == 0) throw java.io.IOException("No radar frames available yet")
            val latest = pastFrames.getJSONObject(pastFrames.length() - 1)

            RadarFrame(host = host, path = latest.getString("path"))
        }
}

data class RadarFrame(val host: String, val path: String) {
    /**
     * Standard Rain Viewer v2 / LibreWXR tile template:
     * {host}{path}/{size}/{z}/{x}/{y}/{color}/{options}.png
     * color=2 and options=1_1 (smoothed, snow shading on) are commonly
     * used defaults - adjust if you'd rather have a different palette.
     */
    fun tileUrlTemplate(size: Int = 256, color: Int = 2, options: String = "1_1"): String =
        "$host$path/$size/{z}/{x}/{y}/$color/$options.png"
}
