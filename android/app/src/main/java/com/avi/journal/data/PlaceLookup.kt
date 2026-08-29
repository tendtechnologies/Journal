package com.avi.journal.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns "where am I" into a short place name, on demand only.
 *
 * Three deliberate limits, because this is a private journal and not a
 * check-in app:
 *
 *  - Coarse accuracy is requested, never fine. A neighbourhood is all a place
 *    label needs, and asking for GPS precision to write down "Oakland" would be
 *    asking for more than the feature uses.
 *  - It runs exactly once per tap. There is no listener, no background updates
 *    and nothing retained — the coordinates are turned into a name and dropped.
 *  - Only the NAME is ever stored on the entry. No latitude or longitude is
 *    written to Firestore, so the journal never accumulates a location history.
 */
class PlaceLookup(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val permission: String = Manifest.permission.ACCESS_COARSE_LOCATION

    /**
     * Best-effort place name, or a failure carrying something worth showing.
     *
     * Callers must have checked [hasPermission] — the SuppressLint is honest
     * rather than a shortcut: the check happens one frame earlier, in the
     * permission launcher that leads here.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentPlace(): Result<String> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Location permission hasn't been granted."))
        }

        return runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(10_000)
                .build()

            val location: Location = client.getCurrentLocation(request, null).await()
                ?: throw IllegalStateException("Couldn't get a location fix. Try again outdoors.")

            describe(location) ?: throw IllegalStateException("Got a location, but no place name for it.")
        }
    }

    /**
     * The blocking Geocoder call, on the IO dispatcher.
     *
     * API 33 added a callback-based overload and deprecated this one, but the
     * deprecated call still works on every supported version and keeps this to
     * one code path instead of two. It does real network work, so it must never
     * run on the main thread — hence the explicit dispatcher rather than trust.
     */
    @Suppress("DEPRECATION")
    private suspend fun describe(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null

        val results = runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
        }.getOrNull()

        val address = results?.firstOrNull() ?: return@withContext null

        // Most specific useful name first. subLocality is a neighbourhood,
        // locality a town or city; falling back through them means a rural fix
        // still produces something rather than nothing.
        val place = address.subLocality
            ?: address.locality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: return@withContext null

        val region = address.adminArea?.takeIf { it != place }
        if (region != null) "$place, $region" else place
    }
}
