package com.flipweather.app

import android.content.Context

/**
 * Thin wrapper around SharedPreferences for the handful of values
 * FlipWeather needs to remember between launches: the saved
 * lat/lon and the NWS radar station + gridpoint derived from it.
 */
object Prefs {
    private const val FILE = "flipweather_prefs"

    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_STATION = "radar_station"
    private const val KEY_OFFICE = "grid_office"
    private const val KEY_GRID_X = "grid_x"
    private const val KEY_GRID_Y = "grid_y"
    private const val KEY_FORECAST_URL = "forecast_url"
    private const val KEY_HOURLY_URL = "hourly_url"
    private const val KEY_OBS_STATIONS_URL = "obs_stations_url"
    private const val KEY_OBS_STATION_ID = "obs_station_id"
    private const val KEY_LOCATION_LABEL = "location_label"
    private const val KEY_LOCATION_IS_GPS = "location_is_gps"
    private const val KEY_LAST_REFRESH_PREFIX = "last_refresh_"
    private const val KEY_CACHED_CURRENT = "cached_current_json"
    private const val KEY_CACHED_DAILY = "cached_daily_json"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasLocation(ctx: Context): Boolean =
        prefs(ctx).contains(KEY_LAT) && prefs(ctx).contains(KEY_LON)

    fun getLatLon(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_LAT) || !p.contains(KEY_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(p.getLong(KEY_LON, 0L))
        return Pair(lat, lon)
    }

    fun setLatLon(ctx: Context, lat: Double, lon: Double) {
        prefs(ctx).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(lon))
            // clear derived gridpoint data since location changed
            .remove(KEY_STATION)
            .remove(KEY_OFFICE)
            .remove(KEY_GRID_X)
            .remove(KEY_GRID_Y)
            .remove(KEY_FORECAST_URL)
            .remove(KEY_HOURLY_URL)
            .remove(KEY_OBS_STATIONS_URL)
            .remove(KEY_OBS_STATION_ID)
            .remove(KEY_CACHED_CURRENT)
            .remove(KEY_CACHED_DAILY)
            .apply()
        OpenMeteoCache.clear()
    }

    fun getLocationLabel(ctx: Context): String? =
        prefs(ctx).getString(KEY_LOCATION_LABEL, null)

    fun setLocationLabel(ctx: Context, label: String) {
        prefs(ctx).edit().putString(KEY_LOCATION_LABEL, label).apply()
    }

    /** Whether the saved location came from GPS (vs. a searched town) - see LocationHelper. */
    fun isLocationFromGps(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOCATION_IS_GPS, false)

    fun setLocationSource(ctx: Context, isGps: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCATION_IS_GPS, isGps).apply()
    }

    // --- Cached NWS gridpoint lookup (avoids re-hitting /points every launch) ---

    fun getGridpoint(ctx: Context): GridpointInfo? {
        val p = prefs(ctx)
        val station = p.getString(KEY_STATION, null) ?: return null
        val office = p.getString(KEY_OFFICE, null) ?: return null
        val gridX = p.getInt(KEY_GRID_X, -1)
        val gridY = p.getInt(KEY_GRID_Y, -1)
        val forecastUrl = p.getString(KEY_FORECAST_URL, null) ?: return null
        val hourlyUrl = p.getString(KEY_HOURLY_URL, null) ?: return null
        val obsStationsUrl = p.getString(KEY_OBS_STATIONS_URL, null) ?: return null
        if (gridX < 0 || gridY < 0) return null
        return GridpointInfo(office, gridX, gridY, station, forecastUrl, hourlyUrl, obsStationsUrl)
    }

    fun setGridpoint(ctx: Context, info: GridpointInfo) {
        prefs(ctx).edit()
            .putString(KEY_STATION, info.radarStation)
            .putString(KEY_OFFICE, info.office)
            .putInt(KEY_GRID_X, info.gridX)
            .putInt(KEY_GRID_Y, info.gridY)
            .putString(KEY_FORECAST_URL, info.forecastUrl)
            .putString(KEY_HOURLY_URL, info.hourlyForecastUrl)
            .putString(KEY_OBS_STATIONS_URL, info.observationStationsUrl)
            .apply()
    }

    // --- Cached nearest observation station id (resolved from observationStationsUrl) ---

    fun getObsStationId(ctx: Context): String? = prefs(ctx).getString(KEY_OBS_STATION_ID, null)

    fun setObsStationId(ctx: Context, stationId: String) {
        prefs(ctx).edit().putString(KEY_OBS_STATION_ID, stationId).apply()
    }

    // --- Last manual-refresh timestamp per screen, for RefreshThrottle ---

    fun getLastRefresh(ctx: Context, screenKey: String): Long =
        prefs(ctx).getLong(KEY_LAST_REFRESH_PREFIX + screenKey, 0L)

    fun setLastRefresh(ctx: Context, screenKey: String, timeMs: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_REFRESH_PREFIX + screenKey, timeMs).apply()
    }

    // --- Cached weather data, so reopening a screen shows the last
    //     successful fetch instantly instead of a blank/loading screen. ---

    fun getCachedCurrentJson(ctx: Context): String? = prefs(ctx).getString(KEY_CACHED_CURRENT, null)

    fun setCachedCurrentJson(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_CACHED_CURRENT, json).apply()
    }

    fun getCachedDailyJson(ctx: Context): String? = prefs(ctx).getString(KEY_CACHED_DAILY, null)

    fun setCachedDailyJson(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_CACHED_DAILY, json).apply()
    }
}
