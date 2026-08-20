package com.flipweather.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Reached from Radar by pressing the right softkey (see RadarActivity).
 * Not a FlipBaseActivity - it's a plain info screen, so the app-wide
 * D-pad Left/Right/Center shortcuts don't apply here; the hardware
 * Back key just returns to Radar via the normal back stack, since
 * RadarActivity doesn't finish() itself when launching this screen.
 */
class RadarOptionsActivity : AppCompatActivity() {

    // Approximate NEXRAD base reflectivity (dBZ) color scale used by the
    // IEM n0q tiles RadarActivity displays.
    private val colorCodes = listOf(
        "#66CC66" to "5-19 dBZ - Very light rain / drizzle",
        "#33A02C" to "20-29 dBZ - Light rain",
        "#FFD500" to "30-39 dBZ - Moderate rain",
        "#FF8C00" to "40-44 dBZ - Heavy rain",
        "#E31A1C" to "45-49 dBZ - Very heavy rain",
        "#E066FF" to "50-59 dBZ - Intense rain, small hail possible",
        "#FFFFFF" to "60+ dBZ - Extreme, large hail likely"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar_options)

        val container = findViewById<LinearLayout>(R.id.colorCodesContainer)
        val inflater = LayoutInflater.from(this)
        for ((hex, label) in colorCodes) {
            val row = inflater.inflate(R.layout.list_item_color_code, container, false)
            row.findViewById<View>(R.id.swatch).setBackgroundColor(Color.parseColor(hex))
            row.findViewById<TextView>(R.id.label).text = label
            container.addView(row)
        }
    }
}
