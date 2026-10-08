package com.flipweather.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Hides the phone's own white softkey-label bar at the bottom of the
 * screen. On keypad phones that bar is the system *navigation bar*, and
 * every FlipWeather screen already draws its own footer labels, so it's
 * redundant. Toggle: Settings > Advanced (Prefs.isHideSystemBar).
 *
 * Same approach as keypad-phone apps like vela-dpad: the Android 11+
 * insets API where available, plus the older sticky-immersive flags on
 * every API level, since vendor keypad ROMs often only honor those.
 * The window is not extended under the bar - content just grows into
 * the freed space - so if a ROM refuses to hide it, our footer still
 * sits above it rather than behind it.
 */
object SystemBars {

    @Suppress("DEPRECATION")
    private const val LEGACY_FLAGS =
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    /** Hides or restores the bar per the user's setting. Call from onResume / on regaining focus. */
    fun apply(activity: Activity) {
        if (Prefs.isHideSystemBar(activity)) hideNavigation(activity) else restoreNavigation(activity)
    }

    fun hideNavigation(activity: Activity) {
        val window = activity.window ?: return
        val decor = window.decorView
        val hide = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.navigationBars())
                }
            }
            @Suppress("DEPRECATION")
            run {
                decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                // Also in the window params, which survive system-initiated clears better.
                val lp = window.attributes
                if (lp.systemUiVisibility and LEGACY_FLAGS != LEGACY_FLAGS) {
                    lp.systemUiVisibility = lp.systemUiVisibility or LEGACY_FLAGS
                    window.attributes = lp
                }
                // If the system brings the bar back (e.g. after a dialog), hide it again.
                decor.setOnSystemUiVisibilityChangeListener { visibility ->
                    if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0 && Prefs.isHideSystemBar(activity)) {
                        decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                    }
                }
            }
        }
        hide()
        // Early in a launch the decor isn't attached to the window yet - re-apply once it is.
        decor.post { hide() }
    }

    fun restoreNavigation(activity: Activity) {
        val window = activity.window ?: return
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.navigationBars())
        }
        @Suppress("DEPRECATION")
        run {
            decor.setOnSystemUiVisibilityChangeListener(null)
            decor.systemUiVisibility = decor.systemUiVisibility and LEGACY_FLAGS.inv()
            val lp = window.attributes
            if (lp.systemUiVisibility and LEGACY_FLAGS != 0) {
                lp.systemUiVisibility = lp.systemUiVisibility and LEGACY_FLAGS.inv()
                window.attributes = lp
            }
        }
    }
}
