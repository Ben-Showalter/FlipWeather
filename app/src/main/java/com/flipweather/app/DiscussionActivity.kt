package com.flipweather.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The local NWS office's latest Area Forecast Discussion - the
 * forecasters' plain-text write-up of what they expect and why. Reached
 * from Settings (Options). D-pad Up/Down scrolls the text.
 */
class DiscussionActivity : FlipBaseActivity() {

    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discussion)

        header = findViewById(R.id.discussionHeader)
        status = findViewById(R.id.discussionStatus)
        body = findViewById(R.id.discussionText)
        scroll = findViewById(R.id.discussionScroll)
        updateBar = UpdateBar(this)
        scroll.requestFocus()
    }

    override val autoRefreshIntervalMs: Long? = RefreshThrottle.DAILY_MIN_MS

    override fun dataFetchedAt(): Long? = Prefs.getCachedAfdAt(this)

    override fun startAutoFetch() {
        RefreshThrottle.markRefreshed(this, "afd")
        fetchFresh()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.hasLocation(this)) {
            status.text = "No location set"
            return
        }
        showFromCache()
        updateBar?.setUpdatedAt(dataFetchedAt())
        maybeAutoRefresh()
    }

    override fun onRefreshKey() {
        if (isFetching) return
        if (!RefreshThrottle.canRefresh(this, "afd", RefreshThrottle.DAILY_MIN_MS)) {
            status.text = RefreshThrottle.waitMessage(this, "afd", RefreshThrottle.DAILY_MIN_MS)
            return
        }
        RefreshThrottle.markRefreshed(this, "afd")
        fetchFresh()
    }

    private fun showFromCache() {
        val cached = Prefs.getCachedAfdJson(this) ?: return
        try {
            val json = JSONObject(cached)
            show(ForecastDiscussion(json.getString("office"), json.getString("issued"), json.getString("text")))
        } catch (e: Exception) {
            // Unreadable cache - the auto-refresh will replace it.
        }
    }

    private fun fetchFresh() {
        if (!Prefs.hasLocation(this)) return
        if (!beginFetch()) return
        val (lat, lon) = Prefs.getLatLon(this)!!
        if (body.text.isNullOrEmpty()) status.text = "Loading forecast discussion..."

        lifecycleScope.launch {
            try {
                val grid = Prefs.getGridpoint(this@DiscussionActivity)
                    ?: NwsApiClient.lookupGridpoint(lat, lon).also { Prefs.setGridpoint(this@DiscussionActivity, it) }
                val afd = NwsApiClient.getLatestDiscussion(grid.office)
                // Only jump back to the top if a newer discussion was issued.
                val isNew = afd.text != body.text.toString()
                show(afd)
                if (isNew) scroll.scrollTo(0, 0)
                Prefs.setCachedAfdJson(this@DiscussionActivity, JSONObject().apply {
                    put("office", afd.office)
                    put("issued", afd.issuanceTime)
                    put("text", afd.text)
                }.toString())
                endFetch(System.currentTimeMillis())
            } catch (e: Exception) {
                status.text = "Couldn't update discussion: ${e.message ?: "network error"}"
                endFetch(null)
            }
        }
    }

    private fun show(afd: ForecastDiscussion) {
        header.text = "Forecast Discussion · ${afd.office}"
        status.text = "Issued ${formatIssued(afd.issuanceTime)}"
        body.text = afd.text.trim()
    }

    private fun formatIssued(iso: String): String {
        val ms = isoToEpochMs(iso) ?: return iso
        return SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(java.util.Date(ms))
    }
}
