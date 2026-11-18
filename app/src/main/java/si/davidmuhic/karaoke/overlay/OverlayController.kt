package si.davidmuhic.karaoke.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/** Adds and removes the [LyricsView] as a full-screen window on top of AGAMA. */
class OverlayController(private val context: Context, private val view: LyricsView) {

    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var attached = false

    var visible = false
        private set

    fun show() {
        if (visible || !Settings.canDrawOverlays(context)) return
        visible = true
        view.animate().cancel()
        if (!attached) {
            try {
                view.alpha = 0f
                windows.addView(view, layoutParams())
                attached = true
            } catch (e: Exception) {
                Log.e(TAG, "Could not add overlay", e)
                visible = false
                return
            }
        }
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        view.refresh()
    }

    fun hide() {
        if (!visible) return
        visible = false
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { detachIfHidden() }.start()
    }

    fun destroy() {
        visible = false
        view.animate().cancel()
        detachIfHidden()
    }

    private fun detachIfHidden() {
        if (visible || !attached) return
        try {
            windows.removeView(view)
        } catch (_: Exception) {
        }
        attached = false
    }

    @Suppress("DEPRECATION")
    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
    }

    private companion object {
        const val TAG = "OverlayController"
        const val FADE_MS = 220L
    }
}
