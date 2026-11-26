package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.example.data.model.Circuit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoStatus(
    val timezoneId: String,
    val isMadridTimezone: Boolean,
    val currentLat: Double?,
    val currentLng: Double?,
    val distanceToActiveCircuitMeters: Float?,
    val isNearActiveCircuit: Boolean,
    val canRideMetropolitano: Boolean
)

class LocationGuard(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    fun isMadridTimezone(): Boolean = TimeZone.getDefault().id == "Europe/Madrid"

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        cont.resume(loc.latitude to loc.longitude)
                    } else {
                        fusedClient.lastLocation
                            .addOnSuccessListener { last ->
                                if (last != null) cont.resume(last.latitude to last.longitude)
                                else cont.resume(null)
                            }
                            .addOnFailureListener { cont.resume(null) }
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (_: Exception) {
            cont.resume(null)
        }
    }

    fun evaluateCircuitAccess(
        circuit: Circuit,
        latLng: Pair<Double, Double>?
    ): GeoStatus {
        val tz = TimeZone.getDefault().id
        val madridTz = tz == "Europe/Madrid"
        val distMeters = if (latLng != null) {
            haversineMeters(latLng.first, latLng.second, circuit.lat, circuit.lng).toFloat()
        } else null
        val near = distMeters != null && distMeters <= circuit.gpsRadiusMeters
        val canRideOfficial = if (!circuit.isOfficial) true else (madridTz && (distMeters == null || distMeters <= 80_000f))
        return GeoStatus(
            timezoneId = tz,
            isMadridTimezone = madridTz,
            currentLat = latLng?.first,
            currentLng = latLng?.second,
            distanceToActiveCircuitMeters = distMeters,
            isNearActiveCircuit = near,
            canRideMetropolitano = canRideOfficial
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
