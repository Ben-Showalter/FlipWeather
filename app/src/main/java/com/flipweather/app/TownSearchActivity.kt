package com.flipweather.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TownSearchActivity : FlipBaseActivity() {

    private lateinit var queryInput: EditText
    private lateinit var status: TextView
    private lateinit var resultsList: ListView
    private var results: List<GeocodeResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_town_search)

        queryInput = findViewById(R.id.queryInput)
        status = findViewById(R.id.searchStatus)
        resultsList = findViewById(R.id.resultsList)

        findViewById<Button>(R.id.searchButton).setOnClickListener { search() }

        resultsList.setOnItemClickListener { _, _, position, _ ->
            val town = results.getOrNull(position) ?: return@setOnItemClickListener
            Prefs.setLatLon(this, town.latitude, town.longitude)
            Prefs.setLocationLabel(this, town.label)
            Prefs.setLocationSource(this, false)
            Toast.makeText(this, "Location set to ${town.label}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun search() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) {
            showStatus("Type a town name first")
            return
        }
        showStatus("Searching...")
        lifecycleScope.launch {
            try {
                results = OpenMeteoApiClient.searchTowns(query)
                if (results.isEmpty()) {
                    showStatus("No matches - try a different spelling")
                    resultsList.adapter = null
                } else {
                    showStatus(null)
                    resultsList.adapter = ArrayAdapter(
                        this@TownSearchActivity,
                        R.layout.list_item_text,
                        android.R.id.text1,
                        results.map { it.label }
                    )
                }
            } catch (e: Exception) {
                showStatus("Search failed: ${e.message ?: "network error"}")
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
}
