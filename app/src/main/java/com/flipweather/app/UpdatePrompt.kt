package com.flipweather.app

import android.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Asks whether to install [release] (noting that Wi-Fi is preferred for the
 * download, and flagging mobile data), and on Install downloads it and hands
 * it to the system installer. Plain AlertDialog buttons, so the D-pad works.
 */
fun AppCompatActivity.showUpdatePrompt(release: UpdateRelease) {
    val sizeMb = String.format(Locale.US, "%.1f", release.apkSizeBytes / 1_048_576.0)
    var message = "A new version of FlipWeather is ready to install ($sizeMb MB).\n\n" +
        "A Wi-Fi connection is preferred for downloading updates."
    if (!UpdateChecker.isOnWifi(this)) message += "\n\nYou are currently on mobile data."
    AlertDialog.Builder(this)
        .setTitle("Update available: v${release.versionName}")
        .setMessage(message)
        .setPositiveButton("Install") { _, _ -> downloadAndInstall(release) }
        .setNegativeButton("Later", null)
        .show()
}

private fun AppCompatActivity.downloadAndInstall(release: UpdateRelease) {
    val progress = AlertDialog.Builder(this)
        .setMessage("Downloading update…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val apk = try {
            UpdateChecker.download(this@downloadAndInstall, release)
        } catch (e: Exception) {
            null
        }
        if (isFinishing || isDestroyed) return@launch
        progress.dismiss()
        if (apk == null) {
            Toast.makeText(this@downloadAndInstall, "Update download failed", Toast.LENGTH_LONG).show()
            return@launch
        }
        try {
            startActivity(UpdateChecker.installIntent(this@downloadAndInstall, apk))
        } catch (e: Exception) {
            Toast.makeText(this@downloadAndInstall, "Update download failed", Toast.LENGTH_LONG).show()
        }
    }
}
