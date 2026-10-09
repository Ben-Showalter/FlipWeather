package com.flipweather.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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

        // Advanced: manual counterpart to the weekly automatic check (FlipBaseActivity).
        val checkUpdatesButton = findViewById<Button>(R.id.checkUpdatesButton)
        checkUpdatesButton.text = "Check for Updates (installed v${UpdateChecker.installedVersion(this)})"
        checkUpdatesButton.setOnClickListener { checkForUpdate() }

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

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = try {
                Result.success(UpdateChecker.fetchLatest(this@SettingsActivity))
            } catch (e: Exception) {
                Result.failure(e)
            }
            val release = result.getOrNull()
            when {
                result.isFailure ->
                    Toast.makeText(this@SettingsActivity, "Couldn't check for updates", Toast.LENGTH_LONG).show()
                release == null ->
                    Toast.makeText(this@SettingsActivity, "FlipWeather is up to date", Toast.LENGTH_SHORT).show()
                else -> showUpdatePrompt(release)
            }
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
