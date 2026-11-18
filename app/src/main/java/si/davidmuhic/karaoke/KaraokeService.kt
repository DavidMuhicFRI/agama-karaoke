package si.davidmuhic.karaoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import si.davidmuhic.karaoke.lyrics.Lyrics
import si.davidmuhic.karaoke.lyrics.LyricsRepository
import si.davidmuhic.karaoke.lyrics.Track
import si.davidmuhic.karaoke.media.MediaTracker
import si.davidmuhic.karaoke.overlay.LyricsView
import si.davidmuhic.karaoke.overlay.OverlayController

/**
 * Long-running service that ties everything together: follows the playing song,
 * fetches its lyrics, and shows or hides the overlay depending on the user and the car's speed.
 */
class KaraokeService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var repository: LyricsRepository
    private lateinit var view: LyricsView
    private lateinit var overlay: OverlayController
    private lateinit var tracker: MediaTracker
    private lateinit var speed: SpeedMonitor

    private var currentTrack: Track? = null

    /** Whether the user (or auto-show) currently wants the lyrics on screen. */
    private var wanted = false

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        startInForeground(null)

        repository = LyricsRepository(this)
        tracker = MediaTracker(this) { onMediaChanged() }
        view = LyricsView(this).apply {
            positionProvider = { tracker.positionMs() }
            playingProvider = { tracker.isPlaying() }
            onDismiss = {
                wanted = false
                updateVisibility()
            }
            fontScale = prefs.fontScale
        }
        overlay = OverlayController(this, view)
        speed = SpeedMonitor(this) { moving -> onSpeedChanged(moving) }

        tracker.start()
        speed.start()
        onMediaChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        view.fontScale = prefs.fontScale
        when (intent?.action) {
            ACTION_TOGGLE -> if (overlay.visible) wanted = false else requestShow()
            ACTION_SHOW -> requestShow()
            ACTION_HIDE -> wanted = false
            ACTION_QUIT -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        overlay.destroy()
        tracker.stop()
        speed.stop()
        repository.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestShow() {
        wanted = true
        if (blockedBySpeed()) {
            Toast.makeText(this, "Lyrics will appear when the car stops", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMediaChanged() {
        val track = tracker.track()
        if (track == null) {
            if (currentTrack != null) {
                currentTrack = null
                view.setTrack("", 0)
                view.setLyrics(Lyrics.NothingPlaying)
                startInForeground(null)
            }
        } else if (track.key != currentTrack?.key) {
            currentTrack = track
            view.setTrack(listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString("  —  "), track.durationMs)
            view.setLyrics(Lyrics.Loading)
            startInForeground(track)
            repository.load(track) { lyrics ->
                if (currentTrack?.key == track.key) view.setLyrics(lyrics)
            }
        }
        view.refresh()
    }

    private fun onSpeedChanged(moving: Boolean) {
        if (!prefs.speedGate) return
        if (moving) {
            // Opened at a red light? Close it when driving off; reopen only by tap or auto-show.
            wanted = false
        } else if (prefs.autoShow && tracker.isPlaying()) {
            wanted = true
        }
        updateVisibility()
    }

    private fun blockedBySpeed(): Boolean = prefs.speedGate && speed.moving

    private fun updateVisibility() {
        if (wanted && !blockedBySpeed()) overlay.show() else overlay.hide()
    }

    private fun startInForeground(track: Track?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Karaoke", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29 && Permissions.location(this)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(track), type)
    }

    private fun buildNotification(track: Track?): Notification {
        val title = track?.title ?: "Karaoke ready"
        val text = track?.artist ?: "Tap \"Karaoke\" in AGAMA to show lyrics"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(servicePendingIntent(ACTION_TOGGLE, 1))
            .addAction(0, "Lyrics", servicePendingIntent(ACTION_TOGGLE, 2))
            .addAction(0, "Quit", servicePendingIntent(ACTION_QUIT, 3))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = intent(this, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    companion object {
        const val ACTION_START = "si.davidmuhic.karaoke.START"
        const val ACTION_TOGGLE = "si.davidmuhic.karaoke.TOGGLE"
        const val ACTION_SHOW = "si.davidmuhic.karaoke.SHOW"
        const val ACTION_HIDE = "si.davidmuhic.karaoke.HIDE"
        const val ACTION_QUIT = "si.davidmuhic.karaoke.QUIT"

        private const val CHANNEL_ID = "karaoke"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        fun intent(context: Context, action: String): Intent =
            Intent(context, KaraokeService::class.java).setAction(action)

        fun send(context: Context, action: String) =
            ContextCompat.startForegroundService(context, intent(context, action))
    }
}
