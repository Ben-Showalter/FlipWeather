package com.flipweather.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Pulls a one-shot GPS fix using the plain LocationManager API - no Play
 * Services needed, which keeps this workable on a low-end/older device.
 *
 * Usage from an Activity:
 *   LocationHelper(this).requestLocation { lat, lon -> ... }
 * and forward onRequestPermissionsResult() to LocationHelper.onPermissionResult().
 */
class LocationHelper(private val activity: Activity) {

    companion object {
        const val PERMISSION_REQUEST_CODE = 4810
        private const val FIX_TIMEOUT_MS = 20000L
    }

    private var pendingCallback: ((Double, Double) -> Unit)? = null
    private var pendingError: (() -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var listener: LocationListener? = null

    fun requestLocation(onResult: (lat: Double, lon: Double) -> Unit, onError: () -> Unit = {}) {
        pendingCallback = onResult
        pendingError = onError

        val hasFine = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        startFix()
    }

    /** Call this from the Activity's onRequestPermissionsResult(). */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startFix()
        } else {
            Toast.makeText(activity, "Location permission denied - enter location manually in Settings", Toast.LENGTH_LONG).show()
            pendingError?.invoke()
        }
    }

    @Suppress("MissingPermission")
    private fun startFix() {
        val lm = activity.getSystemService(Activity.LOCATION_SERVICE) as LocationManager

        // Fall back to last known fix immediately if we have one, then try
        // to get a fresh fix; whichever completes first via the timeout wins.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (lm.isProviderEnabled(p)) {
                val last = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                if (last != null && (best == null || last.accuracy < best!!.accuracy)) {
                    best = last
                }
            }
        }

        var delivered = false
        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered) return
                delivered = true
                cleanup(lm)
                pendingCallback?.invoke(location.latitude, location.longitude)
            }
            @Deprecated("deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = newListener

        var requestedAny = false
        for (p in providers) {
            if (lm.isProviderEnabled(p)) {
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, newListener)
                    requestedAny = true
                } catch (e: SecurityException) { /* ignore, permission already checked */ }
            }
        }

        if (!requestedAny && best == null) {
            Toast.makeText(activity, "No location provider enabled - enable GPS or set location manually", Toast.LENGTH_LONG).show()
            pendingError?.invoke()
            return
        }

        // Timeout: use the best last-known fix if a fresh one hasn't arrived yet.
        handler.postDelayed({
            if (!delivered) {
                delivered = true
                cleanup(lm)
                if (best != null) {
                    pendingCallback?.invoke(best!!.latitude, best!!.longitude)
                } else {
                    Toast.makeText(activity, "Could not get a GPS fix - try again outdoors or enter location manually", Toast.LENGTH_LONG).show()
                    pendingError?.invoke()
                }
            }
        }, FIX_TIMEOUT_MS)
    }

    private fun cleanup(lm: LocationManager) {
        listener?.let {
            try { lm.removeUpdates(it) } catch (e: SecurityException) {}
        }
        listener = null
    }
}
