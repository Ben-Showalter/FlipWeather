package com.flipweather.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : FlipBaseActivity() {

    private lateinit var currentLocationText: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var locationHelper: LocationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        currentLocationText = findViewById(R.id.currentLocationText)
        settingsStatus = findViewById(R.id.settingsStatus)
        locationHelper = LocationHelper(this)

        findViewById<Button>(R.id.searchTownButton).setOnClickListener {
            startActivity(Intent(this, TownSearchActivity::class.java))
        }

        findViewById<Button>(R.id.radarLegendButton).setOnClickListener {
            startActivity(Intent(this, RadarOptionsActivity::class.java))
        }

        findViewById<Button>(R.id.useGpsButton).setOnClickListener {
            settingsStatus.text = "Getting GPS fix..."
            locationHelper.requestLocation(
                onResult = { lat, lon ->
                    Prefs.setLocationSource(this, true)
                    saveLocation(lat, lon, "Current GPS Location")
                },
                onError = { settingsStatus.text = "Could not get GPS fix - try Search for a Town instead" }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocationText()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationHelper.onPermissionResult(requestCode, grantResults)
    }

    private fun saveLocation(lat: Double, lon: Double, label: String) {
        Prefs.setLatLon(this, lat, lon)
        Prefs.setLocationLabel(this, label)
        refreshLocationText()
        Toast.makeText(this, "Location saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLocationText() {
        currentLocationText.text = Prefs.getLocationLabel(this) ?: "Not set"
    }
}
