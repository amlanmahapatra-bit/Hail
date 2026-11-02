package app.hail.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject

/**
 * The phone's location for "pickup = where I am". Read once, only when the user sends a ride
 * request, from the last fix Android already has (no tracking, no background updates).
 */
object Here {
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun now(ctx: Context): JSONObject? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val best = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < MAX_AGE_MS }
            .minByOrNull { it.accuracy }
            ?: return null
        return JSONObject().put("lat", best.latitude).put("lng", best.longitude).put("label", "Current location")
    }
}
