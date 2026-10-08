package com.flipweather.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Every forecast hour across all days in one continuous list, opened at
 * the first hour of the day picked on Daily - scrolling past midnight
 * just carries on into the next day, with the header caption
 * ("Today", "Tomorrow", "Wednesday"...) following along.
 *
 * Hours within NWS's ~7-day hourly forecast come from NWS; dates beyond
 * that (Daily's Open-Meteo-filled tail) fall back to Open-Meteo's hourly
 * data, marked off by a banner row.
 */
class HourlyActivity : FlipBaseActivity() {

    private lateinit var listView: ListView
    private lateinit var header: TextView
    private lateinit var date: String
    private var items: List<Any> = emptyList() // HourlyRow, or ListBanner where Open-Meteo hours start
    private var openedAtDate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hourly)

        date = intent.getStringExtra("date") ?: ""
        header = findViewById(R.id.hourlyHeader)
        header.text = "Hourly · ${dayCaption(date)}"
        updateBar = UpdateBar(this)

        listView = findViewById(R.id.hourlyList)
        // D-pad moves the selection; touch/scroll moves the first visible
        // row - either way the caption tracks the day being looked at.
        listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCaption(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (listView.selectedItemPosition == AdapterView.INVALID_POSITION) updateCaption(firstVisibleItem)
            }
        })
    }

    override val autoRefreshIntervalMs: Long? = RefreshThrottle.DAILY_MIN_MS

    override fun dataFetchedAt(): Long? = Prefs.getCachedHourlyAt(this)

    override fun startAutoFetch() {
        OpenMeteoCache.clear()
        refreshLocationIfGpsThenRun { fetchFresh() }
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.hasLocation(this)) {
            showMessage("No location set")
            return
        }
        showFromCache()
        updateBar?.setUpdatedAt(dataFetchedAt())
        maybeAutoRefresh()
    }

    private fun showFromCache() {
        val cached = Prefs.getCachedHourlyJson(this) ?: return
        try {
            val array = JSONArray(cached)
            val rows = ArrayList<HourlyRow>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                rows.add(
                    HourlyRow(
                        date = o.getString("date"),
                        hour = o.getString("hour"),
                        tempF = if (o.isNull("temp")) null else o.getInt("temp"),
                        precipProbPercent = if (o.isNull("precip")) null else o.getInt("precip"),
                        description = o.getString("desc"),
                        iconRes = o.getInt("icon"),
                        fromOpenMeteo = o.optBoolean("meteo", false)
                    )
                )
            }
            showRows(rows)
        } catch (e: Exception) {
            // Unreadable cache - the auto-refresh will replace it.
        }
    }

    private fun fetchFresh() {
        if (!Prefs.hasLocation(this)) return
        if (!beginFetch()) return
        val (lat, lon) = Prefs.getLatLon(this)!!

        lifecycleScope.launch {
            try {
                val nwsHours = try {
                    val grid = Prefs.getGridpoint(this@HourlyActivity)
                        ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@HourlyActivity, it) }
                    NwsApiClient.getForecastPeriods(grid.hourlyForecastUrl)
                } catch (e: Exception) {
                    emptyList()
                }

                val rows = ArrayList<HourlyRow>()
                nwsHours.mapTo(rows) { p ->
                    HourlyRow(
                        date = p.date,
                        hour = p.startTime.substringAfter("T").take(5),
                        tempF = p.temperature,
                        precipProbPercent = p.precipProbPercent,
                        description = p.shortForecast.ifBlank { "—" },
                        iconRes = WeatherIcons.drawableForNws(p.icon, p.shortForecast),
                        fromOpenMeteo = false
                    )
                }

                // Open-Meteo only for whole dates NWS's hourly forecast doesn't reach.
                val lastNwsDate = nwsHours.lastOrNull()?.date
                val forecast = OpenMeteoCache.forecast
                    ?: OpenMeteoApiClient.getForecast(lat, lon, 16).also { OpenMeteoCache.set(it) }
                forecast.hourly
                    .filter { lastNwsDate == null || it.date > lastNwsDate }
                    .mapTo(rows) { e ->
                        HourlyRow(
                            date = e.date,
                            hour = e.hour,
                            tempF = e.tempF,
                            precipProbPercent = e.precipProbPercent,
                            description = WeatherIcons.labelFor(e.weatherCode),
                            iconRes = WeatherIcons.drawableFor(e.weatherCode),
                            fromOpenMeteo = true
                        )
                    }

                showRows(rows)

                val array = JSONArray()
                for (row in rows) {
                    array.put(JSONObject().apply {
                        put("date", row.date)
                        put("hour", row.hour)
                        put("temp", row.tempF ?: JSONObject.NULL)
                        put("precip", row.precipProbPercent ?: JSONObject.NULL)
                        put("desc", row.description)
                        put("icon", row.iconRes)
                        put("meteo", row.fromOpenMeteo)
                    })
                }
                Prefs.setCachedHourlyJson(this@HourlyActivity, array.toString())
                endFetch(System.currentTimeMillis())
            } catch (e: Exception) {
                if (items.isEmpty()) showMessage("Couldn't load hourly data: ${e.message ?: "network error"}")
                endFetch(null)
            }
        }
    }

    private fun showRows(rows: List<HourlyRow>) {
        // Drop hours that have already passed, so "Today" starts at now.
        val nowKey = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(java.util.Date())
        val upcoming = rows.filter { "${it.date}T${it.hour.take(2)}" >= nowKey }

        val withBanner = ArrayList<Any>()
        var bannerShown = false
        for (row in upcoming) {
            if (!bannerShown && row.fromOpenMeteo) {
                withBanner.add(ListBanner.OPEN_METEO)
                bannerShown = true
            }
            withBanner.add(row)
        }

        // On a refresh, stay on the hour being looked at instead of jumping back.
        val current = (items.getOrNull(listView.selectedItemPosition) as? HourlyRow)
            ?: (items.getOrNull(listView.firstVisiblePosition) as? HourlyRow)
        items = withBanner
        listView.adapter = HourlyAdapter(items)

        val target = if (!openedAtDate || current == null) {
            items.indexOfFirst { it is HourlyRow && it.date >= date }
        } else {
            items.indexOfFirst { it is HourlyRow && it.date == current.date && it.hour == current.hour }
        }
        openedAtDate = true
        val position = if (target >= 0) target else 0
        if (items.isNotEmpty()) {
            listView.setSelection(position)
            updateCaption(position)
        }
    }

    private fun showMessage(msg: String) {
        items = emptyList()
        listView.adapter = ArrayAdapter(this, R.layout.list_item_text, android.R.id.text1, listOf(msg))
    }

    private fun updateCaption(position: Int) {
        // A banner row belongs to the day of the hour right after it.
        val row = (items.getOrNull(position) as? HourlyRow)
            ?: (items.getOrNull(position + 1) as? HourlyRow)
            ?: return
        header.text = "Hourly · ${dayCaption(row.date)}"
    }

    private fun hourLabel(hhmm: String): String = try {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(hhmm)
        SimpleDateFormat("h a", Locale.US).format(parsed!!)
    } catch (e: Exception) {
        hhmm
    }

    private inner class HourlyAdapter(private val items: List<Any>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is ListBanner) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] !is ListBanner

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            if (item is ListBanner) {
                val banner = convertView ?: LayoutInflater.from(this@HourlyActivity)
                    .inflate(R.layout.list_item_banner, parent, false)
                (banner as TextView).text = item.text
                return banner
            }
            val entry = item as HourlyRow
            val view = convertView ?: LayoutInflater.from(this@HourlyActivity)
                .inflate(R.layout.list_item_weather, parent, false)

            view.findViewById<ImageView>(R.id.rowIcon).setImageResource(entry.iconRes)

            val precip = entry.precipProbPercent?.let { " · $it% precip" } ?: ""
            val temp = entry.tempF?.let { "$it°" } ?: "--"
            view.findViewById<TextView>(R.id.rowText).text =
                "${hourLabel(entry.hour)}\n${entry.description} · $temp$precip"

            return view
        }
    }
}

/**
 * "Today", "Tomorrow", the weekday name ("Wednesday") for the rest of the
 * coming week, then "Wed, Oct 14" beyond that.
 */
fun dayCaption(isoDate: String): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val day = try {
        Calendar.getInstance().apply { time = fmt.parse(isoDate)!! }
    } catch (e: Exception) {
        return isoDate
    }
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    // Round, since a DST change makes one day 23 or 25 hours long.
    val daysAhead = Math.round((day.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
    return when {
        daysAhead == 0 -> "Today"
        daysAhead == 1 -> "Tomorrow"
        daysAhead in 2..6 -> SimpleDateFormat("EEEE", Locale.US).format(day.time)
        else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(day.time)
    }
}

/** One merged row on the Hourly list, regardless of which API it came from. */
private data class HourlyRow(
    val date: String,      // "2026-08-20"
    val hour: String,      // "14:00"
    val tempF: Int?,
    val precipProbPercent: Int?,
    val description: String,
    val iconRes: Int,
    val fromOpenMeteo: Boolean
)
