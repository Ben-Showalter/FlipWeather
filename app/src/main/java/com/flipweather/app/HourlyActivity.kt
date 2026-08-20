package com.flipweather.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hours for a date within NWS's ~7-day hourly forecast come from NWS;
 * dates beyond that (Daily's Open-Meteo-filled tail) fall back to
 * Open-Meteo's hourly data for the same date.
 */
class HourlyActivity : FlipBaseActivity() {

    private lateinit var listView: ListView
    private lateinit var date: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hourly)

        date = intent.getStringExtra("date") ?: ""
        findViewById<TextView>(R.id.hourlyHeader).text = "Hourly - ${dayLabel(date)}"

        listView = findViewById(R.id.hourlyList)
        load()
    }

    override fun onRefreshKey() {
        if (!RefreshThrottle.canRefresh(this, "daily", RefreshThrottle.DAILY_MIN_MS)) {
            listView.adapter = ArrayAdapter(
                this, R.layout.list_item_text, android.R.id.text1,
                listOf(RefreshThrottle.waitMessage(this, "daily", RefreshThrottle.DAILY_MIN_MS))
            )
            return
        }
        RefreshThrottle.markRefreshed(this, "daily")
        OpenMeteoCache.clear()
        refreshLocationIfGpsThenRun { load() }
    }

    private fun load() {
        if (!Prefs.hasLocation(this)) {
            listView.adapter = ArrayAdapter(
                this, R.layout.list_item_text, android.R.id.text1, listOf("No location set")
            )
            return
        }
        val (lat, lon) = Prefs.getLatLon(this)!!

        lifecycleScope.launch {
            try {
                val nwsHours = try {
                    val grid = Prefs.getGridpoint(this@HourlyActivity)
                        ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@HourlyActivity, it) }
                    NwsApiClient.getForecastPeriods(grid.hourlyForecastUrl).filter { it.date == date }
                } catch (e: Exception) {
                    emptyList()
                }

                val rows: List<HourlyRow> = if (nwsHours.isNotEmpty()) {
                    nwsHours.map { p ->
                        HourlyRow(
                            hour = p.startTime.substringAfter("T").take(5),
                            tempF = p.temperature,
                            precipProbPercent = p.precipProbPercent,
                            description = p.shortForecast.ifBlank { "—" },
                            iconRes = WeatherIcons.drawableForNws(p.icon, p.shortForecast)
                        )
                    }
                } else {
                    val forecast = OpenMeteoCache.forecast
                        ?: OpenMeteoApiClient.getForecast(lat, lon, 16).also { OpenMeteoCache.set(it) }
                    forecast.hourly.filter { it.date == date }.map { e ->
                        HourlyRow(
                            hour = e.hour,
                            tempF = e.tempF,
                            precipProbPercent = e.precipProbPercent,
                            description = WeatherIcons.labelFor(e.weatherCode),
                            iconRes = WeatherIcons.drawableFor(e.weatherCode)
                        )
                    }
                }

                listView.adapter = HourlyAdapter(rows)
            } catch (e: Exception) {
                listView.adapter = ArrayAdapter(
                    this@HourlyActivity,
                    R.layout.list_item_text,
                    android.R.id.text1,
                    listOf("Couldn't load hourly data: ${e.message ?: "network error"}")
                )
            }
        }
    }

    private fun dayLabel(isoDate: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)
        SimpleDateFormat("EEE, MMM d", Locale.US).format(parsed!!)
    } catch (e: Exception) {
        isoDate
    }

    private fun hourLabel(hhmm: String): String = try {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(hhmm)
        SimpleDateFormat("h a", Locale.US).format(parsed!!)
    } catch (e: Exception) {
        hhmm
    }

    private inner class HourlyAdapter(private val items: List<HourlyRow>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@HourlyActivity)
                .inflate(R.layout.list_item_weather, parent, false)
            val entry = items[position]

            view.findViewById<ImageView>(R.id.rowIcon).setImageResource(entry.iconRes)

            val precip = entry.precipProbPercent?.let { " · $it% precip" } ?: ""
            val temp = entry.tempF?.let { "$it°" } ?: "--"
            view.findViewById<TextView>(R.id.rowText).text =
                "${hourLabel(entry.hour)}\n${entry.description} · $temp$precip"

            return view
        }
    }
}

/** One merged row on the Hourly list, regardless of which API it came from. */
private data class HourlyRow(
    val hour: String,      // "14:00"
    val tempF: Int?,
    val precipProbPercent: Int?,
    val description: String,
    val iconRes: Int
)
