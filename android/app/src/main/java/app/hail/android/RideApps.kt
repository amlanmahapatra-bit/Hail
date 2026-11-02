package app.hail.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

data class Place(val lat: Double, val lng: Double, val label: String) {
    companion object {
        fun from(o: JSONObject) = Place(o.getDouble("lat"), o.getDouble("lng"), o.getString("label"))
    }
}

/** Launches ride apps using the deep-link templates from GET /v1/config/readers. */
object RideApps {
    val packages = mapOf(
        "rapido" to "com.rapido.passenger",
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",
    )

    @Volatile var config: JSONObject? = null

    fun isInstalled(ctx: Context, app: String): Boolean =
        packages[app]?.let { ctx.packageManager.getLaunchIntentForPackage(it) } != null

    /**
     * Opens [app] for the trip. Uses the coordinate deep link if the config has one.
     * Otherwise it opens the app's home screen (no guessing link formats). Returns false
     * if the app is not installed.
     */
    fun launch(ctx: Context, app: String, pickup: Place, drop: Place): Boolean {
        val pkg = packages[app] ?: return false
        val template = config?.optJSONObject("apps")?.optJSONObject(app)
            ?.optString("deep_link")?.takeIf { it.isNotBlank() && it != "null" }
        val intent = if (template != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(fill(template, pickup, drop))).setPackage(pkg)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    private fun fill(t: String, p: Place, d: Place): String = t
        .replace("{pickup_lat}", p.lat.toString()).replace("{pickup_lng}", p.lng.toString())
        .replace("{pickup_label}", Uri.encode(p.label))
        .replace("{drop_lat}", d.lat.toString()).replace("{drop_lng}", d.lng.toString())
        .replace("{drop_label}", Uri.encode(d.label))
}
