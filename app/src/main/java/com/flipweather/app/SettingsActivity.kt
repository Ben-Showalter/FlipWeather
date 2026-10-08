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
    private lateinit var optionsKeyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        currentLocationText = findViewById(R.id.currentLocationText)
        settingsStatus = findViewById(R.id.settingsStatus)
        locationHelper = LocationHelper(this)

        findViewById<Button>(R.id.searchTownButton).setOnClickListener {
            startActivity(Intent(this, TownSearchActivity::class.java))
        }

        // Advanced: which softkey opens Options (see FlipBaseActivity).
        optionsKeyButton = findViewById(R.id.optionsKeyButton)
        showOptionsKey()
        optionsKeyButton.setOnClickListener {
            Prefs.setOptionsOnLeft(this, !Prefs.isOptionsOnLeft(this))
            showOptionsKey()
        }

        // Advanced: hide the phone's own white softkey-label bar (see SystemBars).
        val systemBarButton = findViewById<Button>(R.id.systemBarButton)
        fun showSystemBar() {
            systemBarButton.text =
                if (Prefs.isHideSystemBar(this)) "Phone softkey bar: Hidden" else "Phone softkey bar: Shown"
        }
        showSystemBar()
        systemBarButton.setOnClickListener {
            Prefs.setHideSystemBar(this, !Prefs.isHideSystemBar(this))
            SystemBars.apply(this)
            showSystemBar()
        }

        findViewById<Button>(R.id.discussionButton).setOnClickListener {
            startActivity(Intent(this, DiscussionActivity::class.java))
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

    private fun showOptionsKey() {
        optionsKeyButton.text =
            if (Prefs.isOptionsOnLeft(this)) "Options key: Left softkey" else "Options key: Right softkey"
    }

    override fun onOptionsSideChanged() = showOptionsKey()

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
