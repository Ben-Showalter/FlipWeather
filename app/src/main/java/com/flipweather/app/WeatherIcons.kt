package com.flipweather.app

/**
 * Open-Meteo returns WMO weather codes (https://open-meteo.com/en/docs -
 * "WMO Weather interpretation codes") instead of icon URLs, so we map
 * them to a small bundled set of flat vector icons - readable at the
 * small size / low color depth of this phone's screen.
 */
object WeatherIcons {

    fun drawableFor(weatherCode: Int): Int = when (weatherCode) {
        0 -> R.drawable.ic_clear
        1, 2 -> R.drawable.ic_partly_cloudy
        3 -> R.drawable.ic_cloudy
        45, 48 -> R.drawable.ic_fog
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> R.drawable.ic_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
        95, 96, 99 -> R.drawable.ic_thunder
        else -> R.drawable.ic_cloudy
    }

    /** Human-readable label for the same WMO codes used by [drawableFor]. */
    fun labelFor(weatherCode: Int): String = when (weatherCode) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Rime Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm w/ Hail"
        else -> "Unknown"
    }

    /**
     * NWS periods carry an icon URL like
     * ".../icons/land/day/tsra,40?size=medium" instead of a WMO code -
     * the segment right before the query string is the condition key.
     * Falls back to keyword-matching shortForecast text if the icon URL
     * is missing or its code isn't recognized.
     */
    fun drawableForNws(iconUrl: String, shortForecast: String): Int {
        val code = iconUrl.substringAfterLast("/").substringBefore("?").substringBefore(",").lowercase()
        return when {
            code.contains("tsra") -> R.drawable.ic_thunder
            code.contains("snow") || code.contains("sleet") || code.contains("fzra") || code.contains("blizzard") -> R.drawable.ic_snow
            code.contains("rain") || code.contains("shower") -> R.drawable.ic_rain
            code.contains("fog") || code.contains("haze") || code.contains("smoke") || code.contains("dust") -> R.drawable.ic_fog
            code == "skc" || code == "hot" -> R.drawable.ic_clear
            code == "few" || code == "sct" -> R.drawable.ic_partly_cloudy
            code == "bkn" || code == "ovc" || code == "wind" || code == "cold" -> R.drawable.ic_cloudy
            else -> drawableForShortForecast(shortForecast)
        }
    }

    /**
     * Text-keyword fallback for whenever there's no structured code to go
     * on - NWS's shortForecast text, or a station observation's
     * textDescription (which has no icon code at all, just prose).
     * Public because CurrentActivity also uses this directly as a
     * fallback when NWS's own hosted icon image fails to load or has
     * nothing rendered for the condition (this happens for some less
     * common conditions, fog/haze variants among them).
     */
    fun drawableForShortForecast(text: String): Int {
        val t = text.lowercase()
        return when {
            t.contains("thunder") -> R.drawable.ic_thunder
            t.contains("snow") || t.contains("sleet") || t.contains("ice") -> R.drawable.ic_snow
            t.contains("rain") || t.contains("shower") || t.contains("drizzle") -> R.drawable.ic_rain
            t.contains("fog") || t.contains("mist") || t.contains("haze") || t.contains("smoke") -> R.drawable.ic_fog
            t.contains("clear") || t.contains("sunny") -> R.drawable.ic_clear
            t.contains("partly") || t.contains("mostly sunny") || t.contains("mostly clear") -> R.drawable.ic_partly_cloudy
            t.contains("cloud") || t.contains("overcast") -> R.drawable.ic_cloudy
            else -> R.drawable.ic_cloudy
        }
    }
}
