package com.flipweather.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer published release, as read from the GitHub Releases API. */
data class UpdateRelease(
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long
)

/**
 * Self-update from this repo's GitHub Releases (same scheme as Flip
 * Launcher): releases are published by hand - a signed release APK
 * attached to a `vX.Y` tag, see CONTRIBUTING.md - and this reads
 * `releases/latest`, which skips drafts and pre-releases.
 *
 * The main screens check about once a week on their own (see
 * FlipBaseActivity.maybeCheckForUpdate); Settings > Advanced has a
 * manual "Check for Updates" too.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Ben-Showalter/FlipWeather/releases/latest"

    const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** After a failed automatic check (offline, rate-limited...), retry about a day later. */
    const val RETRY_AFTER_FAILURE_MS = 24L * 60 * 60 * 1000

    private const val TIMEOUT_MS = 15_000

    /**
     * The latest release if it's newer than the installed version and has an
     * APK attached, else null. Throws on network/parse errors.
     */
    suspend fun fetchLatest(context: Context): UpdateRelease? = withContext(Dispatchers.IO) {
        val conn = open(LATEST_RELEASE_URL).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val json = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }

        val version = json.getString("tag_name").removePrefix("v").removePrefix("V")
        if (!isNewer(version, installedVersion(context))) return@withContext null

        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return@withContext UpdateRelease(
                    versionName = version,
                    apkUrl = asset.getString("browser_download_url"),
                    apkSizeBytes = asset.optLong("size")
                )
            }
        }
        null
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** True if dotted version [candidate] (e.g. "1.10.2") is higher than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // "1.2-beta" -> [1, 2]: anything after the first non-numeric part is ignored.
    private fun parts(version: String): List<Int> =
        version.split('.').map { it.takeWhile(Char::isDigit) }
            .takeWhile { it.isNotEmpty() }
            .map { it.toInt() }

    /** Downloads [release]'s APK, replacing any earlier download, and returns the file. */
    suspend fun download(context: Context, release: UpdateRelease): File = withContext(Dispatchers.IO) {
        val file = File(downloadDir(context), "update.apk")
        file.parentFile?.mkdirs()
        // github.com download links redirect (https -> https) to a CDN host,
        // which HttpURLConnection follows on its own.
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        file
    }

    /**
     * Hands [apk] to the system package installer. On Android 8+ the installer
     * itself asks the user to allow "Install unknown apps" for FlipWeather the
     * first time, then carries on with the install.
     */
    fun installIntent(context: Context, apk: File): Intent {
        // minSdk 24 (Android 7), so content:// through the FileProvider always works.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Shared with the installer through the FileProvider (res/xml/file_paths.xml).
    private fun downloadDir(context: Context): File = File(context.cacheDir, "updates")

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "FlipWeather")
        }
}
