package app.hail.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** lat/lng are null for places known only by name (found with the ride app's own search). */
data class Place(val lat: Double?, val lng: Double?, val label: String) {
    val hasCoords: Boolean get() = lat != null && lng != null

    companion object {
        fun from(o: JSONObject) = Place(
            if (o.isNull("lat")) null else o.getDouble("lat"),
            if (o.isNull("lng")) null else o.getDouble("lng"),
            o.getString("label"),
        )
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
    @Volatile var readers: Map<String, AppReaderConfig> = emptyMap()

    /** Reader config from the backend (fixable remotely), else the copy bundled in the APK. */
    fun ensureConfig(ctx: Context, fetch: () -> JSONObject?) {
        if (config != null && readers.isNotEmpty()) return
        val c = runCatching { fetch() }.getOrNull()
            ?: runCatching { JSONObject(ctx.assets.open("readers_config.json").bufferedReader().readText()) }.getOrNull()
            ?: return
        config = c
        readers = runCatching { ReaderConfigs.parse(c) }.getOrDefault(emptyMap())
    }

    fun productName(app: String, key: String): String {
        val products = config?.optJSONObject("apps")?.optJSONObject(app)?.optJSONObject("products") ?: return key
        return products.keys().asSequence().firstOrNull { products.getJSONObject(it).optString("key") == key } ?: key
    }

    fun isInstalled(ctx: Context, app: String): Boolean =
        packages[app]?.let { ctx.packageManager.getLaunchIntentForPackage(it) } != null

    /** "Connected" = installed and switched on in Hail. Never a stored login. */
    fun connected(ctx: Context, prefs: Prefs): List<String> =
        packages.keys.filter { it in prefs.enabledApps && isInstalled(ctx, it) }

    /** Opens [app] via its coordinate deep link (fresh task). False if it has none or it fails. */
    fun launchDeepLink(ctx: Context, app: String, pickup: Place, drop: Place): Boolean {
        val pkg = packages[app] ?: return false
        if (!pickup.hasCoords || !drop.hasCoords) return false // deep links need coordinates
        val template = config?.optJSONObject("apps")?.optJSONObject(app)
            ?.optString("deep_link")?.takeIf { it.isNotBlank() && it != "null" } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fill(template, pickup, drop))).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /** Opens [app] on its home screen, starting fresh so no old trip is left on screen. */
    fun launchHome(ctx: Context, app: String): Boolean {
        val pkg = packages[app] ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /** For the user's chosen ride: deep link if there is one, else the app's home screen. */
    fun launch(ctx: Context, app: String, pickup: Place, drop: Place): Boolean =
        launchDeepLink(ctx, app, pickup, drop) || launchHome(ctx, app)

    private fun fill(t: String, p: Place, d: Place): String = t
        .replace("{pickup_lat}", p.lat.toString()).replace("{pickup_lng}", p.lng.toString())
        .replace("{pickup_label}", Uri.encode(p.label))
        .replace("{drop_lat}", d.lat.toString()).replace("{drop_lng}", d.lng.toString())
        .replace("{drop_label}", Uri.encode(d.label))
}
