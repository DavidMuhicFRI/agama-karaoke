package si.davidmuhic.karaoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the service when the head unit boots, if enabled in setup. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).autoStart || !Permissions.essentials(context)) return
        try {
            KaraokeService.send(context, KaraokeService.ACTION_START)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not start karaoke service", e)
        }
    }
}
