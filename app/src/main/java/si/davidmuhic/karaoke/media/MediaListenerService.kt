package si.davidmuhic.karaoke.media

import android.service.notification.NotificationListenerService
import android.util.Log
import si.davidmuhic.karaoke.KaraokeService
import si.davidmuhic.karaoke.Prefs

/**
 * Exists so the user can grant notification access, which is what unlocks
 * reading other apps' media sessions. The system also binds it at boot,
 * which is a convenient moment to start the karaoke service.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Prefs(this).autoStart && !KaraokeService.running) {
            try {
                KaraokeService.send(this, KaraokeService.ACTION_START)
            } catch (e: Exception) {
                Log.w("MediaListenerService", "Could not auto-start karaoke service", e)
            }
        }
    }
}
