package si.davidmuhic.karaoke.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import si.davidmuhic.karaoke.lyrics.Track

/**
 * Follows whichever media app is playing (Spotify, YouTube Music, …) through the
 * system media sessions. Needs notification access, granted to [MediaListenerService].
 */
class MediaTracker(context: Context, private val onChange: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        select(list.orEmpty())
    }

    /** Safety net: some head units don't report session changes reliably. */
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted yet", e)
        }
        main.post(poll)
    }

    fun stop() {
        main.removeCallbacks(poll)
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun refresh() {
        val list = try {
            sessions.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }
        select(list)
    }

    fun track(): Track? {
        val md = controller?.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        if (title.isBlank()) return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        return Track(title.trim(), artist.trim(), album.trim(), md.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    fun isPlaying(): Boolean = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * Apps only report the position on play/pause/seek, so between reports
     * it is extrapolated from the time elapsed since the last one.
     */
    fun positionMs(): Long {
        val state = controller?.playbackState ?: return 0
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * speed).toLong()
        }
        return position.coerceAtLeast(0)
    }

    private fun select(list: List<MediaController>) {
        val candidates = list.filter { it.metadata != null }
        val best = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: candidates.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return

        controller?.unregisterCallback(controllerCallback)
        controller = best
        best?.registerCallback(controllerCallback, main)
        onChange()
    }

    private companion object {
        const val TAG = "MediaTracker"
        const val POLL_MS = 3_000L
    }
}
