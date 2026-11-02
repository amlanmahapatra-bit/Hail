package app.hail.android

import org.json.JSONArray
import org.json.JSONObject

/** Builds [AppReaderConfig]s from the JSON served at GET /v1/config/readers. */
object ReaderConfigs {

    fun parse(root: JSONObject): Map<String, AppReaderConfig> {
        val price = Regex(root.getString("price_pattern"))
        val dismiss = strings(root.optJSONArray("dismiss_text"))
        val apps = root.getJSONObject("apps")
        return apps.keys().asSequence().associateWith { app -> parseApp(app, apps.getJSONObject(app), price, dismiss) }
    }

    private fun parseApp(app: String, o: JSONObject, price: Regex, dismiss: List<String>): AppReaderConfig {
        val screens = o.optJSONObject("screens") ?: JSONObject()
        val products = o.optJSONObject("products") ?: JSONObject()
        return AppReaderConfig(
            app = app,
            fareList = match(screens.optJSONObject("fare_list")),
            loading = match(screens.optJSONObject("loading")),
            loggedOut = match(screens.optJSONObject("logged_out")),
            rideInProgress = match(screens.optJSONObject("ride_in_progress")),
            etaPatterns = strings(o.optJSONArray("eta_patterns")).map { Regex(it, RegexOption.IGNORE_CASE) },
            products = products.keys().asSequence().map { name ->
                val p = products.getJSONObject(name)
                ProductSpec(
                    name = name,
                    key = p.getString("key"),
                    rideType = p.optString("ride_type").takeIf { it.isNotEmpty() },
                    exclude = p.optString("exclude").takeIf { it.isNotEmpty() },
                )
            }.toList(),
            pricePattern = price,
            nav = NavConfig(strings(o.optJSONObject("nav")?.optJSONArray("search_bar")), dismiss),
        )
    }

    private fun match(o: JSONObject?) =
        ScreenMatch(strings(o?.optJSONArray("all_text")), strings(o?.optJSONArray("any_text")))

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}
