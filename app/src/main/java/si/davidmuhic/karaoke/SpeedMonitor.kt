package si.davidmuhic.karaoke

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

/**
 * Tells whether the car is moving, from GPS speed. Uses hysteresis so
 * GPS jitter at a red light doesn't flip the state back and forth.
 */
class SpeedMonitor(context: Context, private val onChange: (moving: Boolean) -> Unit) : LocationListener {

    private val appContext = context.applicationContext
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var moving = false
        private set

    /** False when location permission or GPS is missing — then the speed gate can't work. */
    var available = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (!Permissions.location(appContext)) return
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            available = true
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable", e)
        }
    }

    fun stop() {
        try {
            locations.removeUpdates(this)
        } catch (_: Exception) {
        }
        available = false
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = location.speed * 3.6f
        val nowMoving = if (moving) kmh > STOP_BELOW_KMH else kmh > MOVE_ABOVE_KMH
        if (nowMoving != moving) {
            moving = nowMoving
            onChange(moving)
        }
    }

    // Older Android versions declare these as abstract, so they must be implemented.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private companion object {
        const val TAG = "SpeedMonitor"
        const val MOVE_ABOVE_KMH = 8f
        const val STOP_BELOW_KMH = 4f
    }
}
