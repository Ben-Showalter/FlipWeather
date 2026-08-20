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
    private var rows: List<DailyRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily)

        status = findViewById(R.id.dailyStatus)
        listView = findViewById(R.id.dailyList)

        listView.setOnItemClickListener { _, _, position, _ ->
            val day = rows.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(Intent(this, HourlyActivity::class.java).putExtra("date", day.date))
        }
    }

    override fun onResume() {
        super.onResume()
        // Reopening this screen shows whatever was last downloaded,
        // instantly - no network call just from navigating back to it.
        // Left softkey (onRefreshKey) is the only thing that re-fetches.
        if (!showFromCache()) {
            fetchFresh()
        }
    }

    override fun onRefreshKey() {
        if (!RefreshThrottle.canRefresh(this, "daily", RefreshThrottle.DAILY_MIN_MS)) {
            showStatus(RefreshThrottle.waitMessage(this, "daily", RefreshThrottle.DAILY_MIN_MS))
            return
        }
        RefreshThrottle.markRefreshed(this, "daily")
        OpenMeteoCache.clear()
        showStatus("Refreshing...")
        refreshLocationIfGpsThenRun { fetchFresh() }
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
                        iconRes = o.getInt("icon")
                    )
                )
            }
            rows = loaded
            showStatus(null)
            listView.adapter = DailyAdapter(rows)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchFresh() {
        if (!Prefs.hasLocation(this)) return
        val (lat, lon) = Prefs.getLatLon(this)!!
        showStatus("Loading $TOTAL_DAYS-day forecast...")

        lifecycleScope.launch {
            try {
                // NWS is US-only and can fail to resolve a gridpoint outside its
                // coverage area - fall back to an all-Open-Meteo list rather than
                // failing the whole screen when that happens.
                val nwsPeriods = try {
                    val grid = Prefs.getGridpoint(this@DailyForecastActivity)
                        ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@DailyForecastActivity, it) }
                    NwsApiClient.getForecastPeriods(grid.forecastUrl)
                } catch (e: Exception) {
                    emptyList()
                }

                val meteoForecast = OpenMeteoCache.forecast
                    ?: OpenMeteoApiClient.getForecast(lat, lon, TOTAL_DAYS).also { OpenMeteoCache.set(it) }

                rows = mergeDailyRows(nwsPeriods, meteoForecast.daily, TOTAL_DAYS)
                showStatus(null)
                listView.adapter = DailyAdapter(rows)

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
                    })
                }
                Prefs.setCachedDailyJson(this@DailyForecastActivity, array.toString())
            } catch (e: Exception) {
                showStatus("Couldn't load forecast: ${e.message ?: "network error"}")
            }
        }
    }

    private fun showStatus(msg: String?) {
        if (msg == null) {
            status.visibility = View.GONE
        } else {
            status.visibility = View.VISIBLE
            status.text = msg
        }
    }

    private inner class DailyAdapter(private val items: List<DailyRow>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@DailyForecastActivity)
                .inflate(R.layout.list_item_weather, parent, false)
            val entry = items[position]

            view.findViewById<ImageView>(R.id.rowIcon).setImageResource(entry.iconRes)

            val high = entry.highF?.let { "$it°" } ?: "--"
            val low = entry.lowF?.let { "$it°" } ?: "--"
            val precip = entry.precipProbPercent?.let { " · $it% precip" } ?: ""
            view.findViewById<TextView>(R.id.rowText).text =
                "${dayLabel(entry.date)}\n${entry.description} · $high / $low$precip"

            return view
        }
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

/** One merged row on the Daily list, regardless of which API it came from. */
data class DailyRow(
    val date: String,
    val highF: Int?,
    val lowF: Int?,
    val precipProbPercent: Int?,
    val description: String,
    val iconRes: Int
)

/**
 * Groups NWS day/night periods into one row per date (day period supplies
 * the high, night period supplies the low), then appends Open-Meteo days
 * for whatever's left after NWS's coverage, up to [totalDays] total.
 */
fun mergeDailyRows(
    nwsPeriods: List<ForecastPeriod>,
    openMeteoDaily: List<DailyForecastEntry>,
    totalDays: Int
): List<DailyRow> {
    data class Accum(
        var highF: Int? = null,
        var lowF: Int? = null,
        var iconUrl: String = "",
        var shortForecast: String = "",
        var precipProbPercent: Int? = null
    )

    val byDate = LinkedHashMap<String, Accum>()
    for (p in nwsPeriods) {
        val accum = byDate.getOrPut(p.date) { Accum() }
        if (p.isDaytime) {
            accum.highF = p.temperature
            accum.iconUrl = p.icon
            accum.shortForecast = p.shortForecast
            if (accum.precipProbPercent == null) accum.precipProbPercent = p.precipProbPercent
        } else {
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
            iconRes = WeatherIcons.drawableForNws(a.iconUrl, a.shortForecast)
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
                iconRes = WeatherIcons.drawableFor(e.weatherCode)
            )
        }

    return (nwsRows + meteoRows).take(totalDays)
}
