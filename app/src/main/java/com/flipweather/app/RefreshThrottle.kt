package com.flipweather.app

import android.content.Context

/**
 * None of NWS, Open-Meteo, or the IEM radar tiles publish a hard rate
 * limit for light personal use - these intervals are courtesy throttles
 * matched to how often each source's underlying data actually changes,
 * so pressing 0 more often than that just re-requests the same data.
 *
 *   Current conditions - NWS station observations update roughly hourly
 *   Daily/Hourly        - NWS/Open-Meteo model runs update roughly hourly
 *   Radar                - NEXRAD volume scans roughly every 5-10 minutes
 */
object RefreshThrottle {

    const val CURRENT_MIN_MS = 10 * 60 * 1000L
    const val DAILY_MIN_MS = 15 * 60 * 1000L
    const val RADAR_MIN_MS = 5 * 60 * 1000L

    fun canRefresh(ctx: Context, screenKey: String, minIntervalMs: Long): Boolean {
        val last = Prefs.getLastRefresh(ctx, screenKey)
        return System.currentTimeMillis() - last >= minIntervalMs
    }

    fun markRefreshed(ctx: Context, screenKey: String) {
        Prefs.setLastRefresh(ctx, screenKey, System.currentTimeMillis())
    }

    /** Human-friendly message for when a refresh is declined as too soon. */
    fun waitMessage(ctx: Context, screenKey: String, minIntervalMs: Long): String {
        val last = Prefs.getLastRefresh(ctx, screenKey)
        val remainingMs = minIntervalMs - (System.currentTimeMillis() - last)
        val remainingMin = (remainingMs / 60000L) + 1
        return "Already up to date - try again in about $remainingMin min"
    }
}
