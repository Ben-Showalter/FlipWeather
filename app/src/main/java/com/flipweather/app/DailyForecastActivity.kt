package com.flipweather.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Days 1-7ish (however far the NWS 7-day/14-period forecast reaches)
 * come straight from NWS - Open-Meteo only fills in the remaining
 * days out to 16 total, since NWS doesn't publish per-day forecasts
 * that far out. See [mergeDailyRows].
 */
class DailyForecastActivity : FlipBaseActivity() {

    companion object {
        private const val TOTAL_DAYS = 16
    }

    private lateinit var status: TextView
    private lateinit var listView: ListView
    private var items: List<Any> = emptyList() // DailyRow, or ListBanner between NWS and Open-Meteo days

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily)

        status = findViewById(R.id.dailyStatus)
        listView = findViewById(R.id.dailyList)
        updateBar = UpdateBar(this)

        listView.setOnItemClickListener { _, _, position, _ ->
            val day = items.getOrNull(position) as? DailyRow ?: return@setOnItemClickListener
            startActivity(Intent(this, HourlyActivity::class.java).putExtra("date", day.date))
        }
    }

    override val autoRefreshIntervalMs: Long? = RefreshThrottle.DAILY_MIN_MS

    override fun dataFetchedAt(): Long? = Prefs.getCachedDailyAt(this)

    override fun startAutoFetch() {
        OpenMeteoCache.clear()
        refreshLocationIfGpsThenRun { fetchFresh() }
    }

    override fun onResume() {
        super.onResume()
        // Reopening this screen shows whatever was last downloaded,
        // instantly, then auto-updates in the background if it's due.
        showFromCache()
        updateBar?.setUpdatedAt(dataFetchedAt())
        maybeAutoRefresh()
    }

    private fun showFromCache(): Boolean {
        if (!Prefs.hasLocation(this)) {
            showStatus("No location set - press Right (Options) to set one")
            return true
        }
        val cached = Prefs.getCachedDailyJson(this) ?: return false
        return try {
            val array = JSONArray(cached)
            val loaded = ArrayList<DailyRow>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                loaded.add(
                    DailyRow(
                        date = o.getString("date"),
                        highF = if (o.isNull("high")) null else o.getInt("high"),
                        lowF = if (o.isNull("low")) null else o.getInt("low"),
                        precipProbPercent = if (o.isNull("precip")) null else o.getInt("precip"),
                        description = o.getString("desc"),
                        iconRes = o.getInt("icon"),
                        // Caches written before these fields existed: assume NWS, no amounts.
                        source = o.optString("source", DailyRow.SOURCE_NWS),
                        dayAmounts = amountsFromJson(o.optJSONObject("dayAmt")),
                        nightAmounts = amountsFromJson(o.optJSONObject("nightAmt"))
                    )
                )
            }
            showRows(loaded)
            showStatus(null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchFresh() {
        if (!Prefs.hasLocation(this)) return
        if (!beginFetch()) return
        val (lat, lon) = Prefs.getLatLon(this)!!
        if (items.isEmpty()) showStatus("Loading $TOTAL_DAYS-day forecast...")

        lifecycleScope.launch {
            try {
                // NWS is US-only and can fail to resolve a gridpoint outside its
                // coverage area - fall back to an all-Open-Meteo list rather than
                // failing the whole screen when that happens.
                val grid = try {
                    Prefs.getGridpoint(this@DailyForecastActivity)
                        ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@DailyForecastActivity, it) }
                } catch (e: Exception) {
                    null
                }
                val (nwsPeriods, precip) = coroutineScope {
                    val periods = async {
                        try {
                            grid?.let { NwsApiClient.getForecastPeriods(it.forecastUrl) } ?: emptyList()
                        } catch (e: Exception) {
                            emptyList<ForecastPeriod>()
                        }
                    }
                    // Rain/snow amounts are a nice-to-have - a failure just omits them.
                    val amounts = async {
                        try {
                            grid?.let { NwsApiClient.getGridpointPrecip(it) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    periods.await() to amounts.await()
                }

                val meteoForecast = OpenMeteoCache.forecast
                    ?: OpenMeteoApiClient.getForecast(lat, lon, TOTAL_DAYS).also { OpenMeteoCache.set(it) }

                val rows = mergeDailyRows(nwsPeriods, meteoForecast.daily, TOTAL_DAYS, precip)
                showStatus(null)
                showRows(rows)

                // Cache so reopening shows this instantly next time.
                val array = JSONArray()
                for (row in rows) {
                    array.put(JSONObject().apply {
                        put("date", row.date)
                        put("high", row.highF ?: JSONObject.NULL)
                        put("low", row.lowF ?: JSONObject.NULL)
                        put("precip", row.precipProbPercent ?: JSONObject.NULL)
                        put("desc", row.description)
                        put("icon", row.iconRes)
                        put("source", row.source)
                        row.dayAmounts?.let { put("dayAmt", amountsToJson(it)) }
                        row.nightAmounts?.let { put("nightAmt", amountsToJson(it)) }
                    })
                }
                Prefs.setCachedDailyJson(this@DailyForecastActivity, array.toString())
                endFetch(System.currentTimeMillis())
            } catch (e: Exception) {
                showStatus("Couldn't update forecast: ${e.message ?: "network error"}")
                endFetch(null)
            }
        }
    }

    /** Lays out the rows, with a banner where the list switches over to Open-Meteo days. */
    private fun showRows(rows: List<DailyRow>) {
        val withBanner = ArrayList<Any>()
        var bannerShown = false
        for (row in rows) {
            if (!bannerShown && row.source == DailyRow.SOURCE_OPEN_METEO) {
                withBanner.add(ListBanner.OPEN_METEO)
                bannerShown = true
            }
            withBanner.add(row)
        }
        items = withBanner
        listView.adapter = DailyAdapter(items)
    }

    private fun showStatus(msg: String?) {
        if (msg == null) {
            status.visibility = View.GONE
        } else {
            status.visibility = View.VISIBLE
            status.text = msg
        }
    }

    private inner class DailyAdapter(private val items: List<Any>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is ListBanner) 1 else 0
        override fun areAllItemsEnabled() = false
        // Banner rows can't be focused/selected, so the D-pad skips over them.
        override fun isEnabled(position: Int) = items[position] !is ListBanner

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            if (item is ListBanner) {
                val banner = convertView ?: LayoutInflater.from(this@DailyForecastActivity)
                    .inflate(R.layout.list_item_banner, parent, false)
                (banner as TextView).text = item.text
                return banner
            }
            val entry = item as DailyRow
            val view = convertView ?: LayoutInflater.from(this@DailyForecastActivity)
                .inflate(R.layout.list_item_weather, parent, false)

            view.findViewById<ImageView>(R.id.rowIcon).setImageResource(entry.iconRes)

            val high = entry.highF?.let { "$it°" } ?: "--"
            val low = entry.lowF?.let { "$it°" } ?: "--"
            val precip = entry.precipProbPercent?.let { " · $it% precip" } ?: ""
            val amounts = amountsLine(entry)?.let { "\n$it" } ?: ""
            view.findViewById<TextView>(R.id.rowText).text =
                "${dayLabel(entry.date)}\n${entry.description} · $high / $low$precip$amounts"

            return view
        }
    }

    /** e.g. "Today: Rain 0.25 inches · Tonight: Snow 1.2 inches" - null when no amounts are expected. */
    private fun amountsLine(row: DailyRow): String? {
        val isToday = row.date == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val parts = ArrayList<String>()
        row.dayAmounts?.takeIf { !it.isZero }?.let {
            parts.add("${if (isToday) "Today" else "Day"}: ${formatAmounts(it)}")
        }
        row.nightAmounts?.takeIf { !it.isZero }?.let {
            parts.add("${if (isToday) "Tonight" else "Night"}: ${formatAmounts(it)}")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun dayLabel(isoDate: String): String {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)
            SimpleDateFormat("EEE, MMM d", Locale.US).format(parsed!!)
        } catch (e: Exception) {
            isoDate
        }
    }
}

/**
 * "Rain 0.25 inches", or "Precip 0.10 inches Snow 1.2 inches" when snow is
 * expected (NWS's precip amount is liquid-equivalent, so it isn't all "rain" then).
 */
fun formatAmounts(a: PrecipAmounts): String {
    val parts = ArrayList<String>()
    val hasSnow = a.snowIn >= 0.05
    if (a.precipIn >= 0.005) parts.add("${if (hasSnow) "Precip" else "Rain"} ${inches(a.precipIn, 2)}")
    if (hasSnow) parts.add("Snow ${inches(a.snowIn, 1)}")
    if (a.iceIn >= 0.005) parts.add("Ice ${inches(a.iceIn, 2)}")
    return parts.joinToString(" ")
}

/** "0.25 inches", or "1 inch" when it rounds to exactly one. */
private fun inches(value: Double, decimals: Int): String {
    val text = "%.${decimals}f".format(Locale.US, value)
    return if (text.toDouble() == 1.0) "1 inch" else "$text inches"
}

private fun amountsToJson(a: PrecipAmounts) = JSONObject().apply {
    put("p", a.precipIn)
    put("s", a.snowIn)
    put("i", a.iceIn)
}

private fun amountsFromJson(o: JSONObject?): PrecipAmounts? =
    o?.let { PrecipAmounts(it.optDouble("p", 0.0), it.optDouble("s", 0.0), it.optDouble("i", 0.0)) }

/** Non-selectable divider row in the Daily/Hourly lists. */
enum class ListBanner(val text: String) {
    OPEN_METEO("Forecast below from Open-Meteo (beyond NWS range)")
}

/** One merged row on the Daily list, regardless of which API it came from. */
data class DailyRow(
    val date: String,
    val highF: Int?,
    val lowF: Int?,
    val precipProbPercent: Int?,
    val description: String,
    val iconRes: Int,
    val source: String = SOURCE_NWS,
    // Expected precip/snow/ice in the NWS day and night periods - null
    // when NWS publishes no amounts that far out (or for Open-Meteo days).
    val dayAmounts: PrecipAmounts? = null,
    val nightAmounts: PrecipAmounts? = null
) {
    companion object {
        const val SOURCE_NWS = "nws"
        const val SOURCE_OPEN_METEO = "meteo"
    }
}

/**
 * Groups NWS day/night periods into one row per date (day period supplies
 * the high, night period supplies the low), then appends Open-Meteo days
 * for whatever's left after NWS's coverage, up to [totalDays] total.
 */
fun mergeDailyRows(
    nwsPeriods: List<ForecastPeriod>,
    openMeteoDaily: List<DailyForecastEntry>,
    totalDays: Int,
    precip: PrecipTimeline? = null
): List<DailyRow> {
    data class Accum(
        var highF: Int? = null,
        var lowF: Int? = null,
        var iconUrl: String = "",
        var shortForecast: String = "",
        var precipProbPercent: Int? = null,
        var dayAmounts: PrecipAmounts? = null,
        var nightAmounts: PrecipAmounts? = null
    )

    fun amountsFor(p: ForecastPeriod): PrecipAmounts? {
        val start = isoToEpochMs(p.startTime) ?: return null
        val end = isoToEpochMs(p.endTime) ?: return null
        return precip?.sumInches(start, end)
    }

    val byDate = LinkedHashMap<String, Accum>()
    for (p in nwsPeriods) {
        val accum = byDate.getOrPut(p.date) { Accum() }
        if (p.isDaytime) {
            accum.dayAmounts = amountsFor(p)
            accum.highF = p.temperature
            accum.iconUrl = p.icon
            accum.shortForecast = p.shortForecast
            if (accum.precipProbPercent == null) accum.precipProbPercent = p.precipProbPercent
        } else {
            accum.nightAmounts = amountsFor(p)
            accum.lowF = p.temperature
            if (accum.iconUrl.isBlank()) {
                accum.iconUrl = p.icon
                accum.shortForecast = p.shortForecast
            }
            if (accum.precipProbPercent == null) accum.precipProbPercent = p.precipProbPercent
        }
    }

    val nwsRows = byDate.entries.sortedBy { it.key }.map { (date, a) ->
        DailyRow(
            date = date,
            highF = a.highF,
            lowF = a.lowF,
            precipProbPercent = a.precipProbPercent,
            description = a.shortForecast.ifBlank { "—" },
            iconRes = WeatherIcons.drawableForNws(a.iconUrl, a.shortForecast),
            dayAmounts = a.dayAmounts,
            nightAmounts = a.nightAmounts
        )
    }

    val lastNwsDate = nwsRows.lastOrNull()?.date
    val meteoRows = openMeteoDaily
        .filter { lastNwsDate == null || it.date > lastNwsDate }
        .map { e ->
            DailyRow(
                date = e.date,
                highF = e.tempMaxF,
                lowF = e.tempMinF,
                precipProbPercent = e.precipProbPercent,
                description = WeatherIcons.labelFor(e.weatherCode),
                iconRes = WeatherIcons.drawableFor(e.weatherCode),
                source = DailyRow.SOURCE_OPEN_METEO
            )
        }

    return (nwsRows + meteoRows).take(totalDays)
}
