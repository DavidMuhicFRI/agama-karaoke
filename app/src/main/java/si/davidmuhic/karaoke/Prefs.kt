package si.davidmuhic.karaoke

import android.content.Context

/** User settings, edited on the setup screen. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    /** Hide lyrics while the car is moving. */
    var speedGate: Boolean
        get() = sp.getBoolean("speed_gate", true)
        set(value) = sp.edit().putBoolean("speed_gate", value).apply()

    /** Pop the lyrics up automatically whenever the car stops with music playing. */
    var autoShow: Boolean
        get() = sp.getBoolean("auto_show", false)
        set(value) = sp.edit().putBoolean("auto_show", value).apply()

    /** Start the background service when the head unit boots. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(value) = sp.edit().putBoolean("auto_start", value).apply()

    /** Lyric text size multiplier. */
    var fontScale: Float
        get() = sp.getFloat("font_scale", 1f)
        set(value) = sp.edit().putFloat("font_scale", value).apply()
}
