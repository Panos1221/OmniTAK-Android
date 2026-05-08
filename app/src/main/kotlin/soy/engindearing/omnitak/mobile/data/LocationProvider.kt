package soy.engindearing.omnitak.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SelfFix(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedKmh: Double,
    val accuracyM: Float,
    val timeMs: Long,
)

class LocationProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _fix = MutableStateFlow<SelfFix?>(null)
    val fix: StateFlow<SelfFix?> = _fix.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _fix.value = it.toSelfFix() }
        }
    }

    private var started = false

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 10_000L): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc ->
            // Suppress fixes older than 5 minutes — better to wait for a fresh
            // one than show last-week's location on cold start.
            if (loc != null && _fix.value == null &&
                System.currentTimeMillis() - loc.time < 5 * 60_000L) {
                _fix.value = loc.toSelfFix()
            }
        }
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        client.removeLocationUpdates(callback)
        started = false
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun Location.toSelfFix() = SelfFix(
        lat = latitude,
        lon = longitude,
        altitudeM = if (hasAltitude()) altitude else 0.0,
        speedKmh = if (hasSpeed()) speed * 3.6 else 0.0,
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        timeMs = time,
    )
}
