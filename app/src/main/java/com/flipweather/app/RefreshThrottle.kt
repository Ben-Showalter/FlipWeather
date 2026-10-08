package com.flipweather.app

/**
 * How often each screen auto-refreshes (see FlipBaseActivity.maybeAutoRefresh).
 * None of NWS, Open-Meteo, or the IEM radar tiles publish a hard rate
 * limit for light personal use - these intervals are courtesy limits
 * matched to how often each source's underlying data actually changes,
 * so refreshing more often than that just re-requests the same data.
 *
 *   Current conditions - NWS station observations update roughly hourly
 *   Daily/Hourly        - NWS/Open-Meteo model runs update roughly hourly
 *   Radar                - NEXRAD volume scans roughly every 5-10 minutes
 */
object RefreshThrottle {
    const val CURRENT_MIN_MS = 10 * 60 * 1000L
    const val DAILY_MIN_MS = 15 * 60 * 1000L
    const val RADAR_MIN_MS = 5 * 60 * 1000L
}
