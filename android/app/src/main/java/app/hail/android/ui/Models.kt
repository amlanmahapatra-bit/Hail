package app.hail.android.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One ride option on the card, parsed from the backend's `recommendation`. */
data class RideOption(
    val id: String,
    val app: String,
    val product: String,
    val rideType: String,
    val fareMin: Double,
    val fareMax: Double,
    val eta: Int?,
    val surge: Boolean,
    val saves: Int?,
) {
    val appName get() = app.replaceFirstChar { it.uppercase() }

    /** "Ola Auto", "Uber Go AC": the app name once, even if the product already starts with it. */
    val title get() = if (product.startsWith(appName, ignoreCase = true)) product else "$appName $product"

    val fare get() = if (fareMin == fareMax) rupees(fareMin) else "${rupees(fareMin)}–${rupees(fareMax)}"

    companion object {
        fun from(o: JSONObject) = RideOption(
            id = o.getString("option_id"),
            app = o.getString("app"),
            product = o.optString("product_name").takeIf { it.isNotEmpty() && it != "null" }
                ?: o.getString("ride_type").replaceFirstChar { it.uppercase() },
            rideType = o.getString("ride_type"),
            fareMin = o.getDouble("fare_min"),
            fareMax = o.getDouble("fare_max"),
            eta = if (o.isNull("eta_minutes")) null else o.getInt("eta_minutes"),
            surge = o.optBoolean("surge_flag"),
            saves = if (o.isNull("saves")) null else o.optInt("saves"),
        )
    }
}

/** The options card. */
data class Recommendation(
    val text: String,
    val bestId: String,
    val options: List<RideOption>,
    val token: String,
    val category: String?,
    val live: Boolean,
    val checkedAt: String?,
    val drop: JSONObject?,
) {
    val best get() = options.first { it.id == bestId }
    val others get() = options.filter { it.id != bestId }

    companion object {
        fun from(out: JSONObject): Recommendation {
            val arr = out.getJSONArray("options")
            return Recommendation(
                text = out.optString("text"),
                bestId = out.getString("recommended_option_id"),
                options = (0 until arr.length()).map { RideOption.from(arr.getJSONObject(it)) },
                token = out.getJSONObject("confirmation").getString("token"),
                category = out.optString("ride_category").takeIf { it.isNotEmpty() && it != "null" },
                live = out.optBoolean("live", true),
                checkedAt = out.optString("prices_checked_at").takeIf { it.isNotEmpty() && it != "null" },
                drop = out.optJSONObject("drop"),
            )
        }
    }
}

fun rupees(v: Double): String =
    if (v % 1.0 == 0.0) "₹${v.toInt()}" else "₹" + String.format(Locale.US, "%.2f", v)

fun clockTime(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    }.getOrNull()
}

/** A ride Hail opened for the user. Kept on the phone only (Rides and Savings tabs). */
data class Trip(
    val at: Long,
    val to: String,
    val app: String,
    val product: String,
    val fare: String,
    val saved: Int?,
    val live: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("at", at).put("to", to).put("app", app)
        .put("product", product).put("fare", fare).put("saved", saved ?: JSONObject.NULL).put("live", live)

    val whenText: String
        get() = DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    companion object {
        fun from(o: JSONObject) = Trip(
            at = o.getLong("at"), to = o.optString("to"), app = o.optString("app"),
            product = o.optString("product"), fare = o.optString("fare"),
            saved = if (o.isNull("saved")) null else o.optInt("saved"), live = o.optBoolean("live", true),
        )
    }
}

/**
 * Ride history, on this phone only (not sent to the backend). Cleared with Delete my data.
 * Test trips (simulated prices) are kept but never counted as savings.
 */
class TripLog(context: Context) {
    private val sp = context.getSharedPreferences("hail_trips", Context.MODE_PRIVATE)

    fun all(): List<Trip> = runCatching {
        val a = JSONArray(sp.getString("trips", "[]"))
        (0 until a.length()).map { Trip.from(a.getJSONObject(it)) }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    fun add(t: Trip) {
        val list = (listOf(t) + all()).take(MAX)
        sp.edit().putString("trips", JSONArray(list.map { it.json() }).toString()).apply()
    }

    fun clear() = sp.edit().clear().apply()

    /** Live savings since the 1st of this month. */
    fun savedThisMonth(now: Long = System.currentTimeMillis()): Int {
        val z = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(now).atZone(z).withDayOfMonth(1).toLocalDate().atStartOfDay(z)
            .toInstant().toEpochMilli()
        return all().filter { it.live && it.at >= start }.sumOf { it.saved ?: 0 }
    }

    companion object {
        private const val MAX = 200
    }
}
