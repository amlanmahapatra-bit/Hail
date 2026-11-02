package app.hail.android

import kotlin.math.max

/**
 * Pure fare-list reader: accessibility nodes in, typed fares out. No Android dependencies,
 * so it is unit-tested on the JVM against fixtures transcribed from real screens.
 *
 * Text read here is untrusted. It is only ever matched against the catalog (product names,
 * screen markers) and number patterns. Nothing free-form leaves this file: a fare is a
 * catalog key plus numbers.
 */

data class ScreenNode(
    val text: String?,
    val desc: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = max(1, bottom - top)
}

data class ProductSpec(val name: String, val key: String, val rideType: String?, val exclude: String?)

data class ScreenMatch(val allText: List<String>, val anyText: List<String>) {
    /** True if every allText marker and (when given) at least one anyText marker is on screen. */
    fun matches(labels: List<String>): Boolean {
        if (allText.isEmpty() && anyText.isEmpty()) return false
        fun present(m: String) = labels.any { it.contains(m, ignoreCase = true) }
        return allText.all(::present) && (anyText.isEmpty() || anyText.any(::present))
    }
}

data class AppReaderConfig(
    val app: String,
    val fareList: ScreenMatch,
    val loading: ScreenMatch,
    val loggedOut: ScreenMatch,
    val rideInProgress: ScreenMatch,
    val etaPatterns: List<Regex>,
    val products: List<ProductSpec>,
    val pricePattern: Regex,
)

enum class ScreenState { LOGGED_OUT, RIDE_IN_PROGRESS, LOADING, FARE_LIST, UNKNOWN }

data class Fare(
    val productKey: String,
    val rideType: String,
    val fareMin: Double,
    val fareMax: Double,
    val etaMinutes: Int?,
)

object FareReader {

    fun state(nodes: List<ScreenNode>, cfg: AppReaderConfig): ScreenState {
        val labels = nodes.mapNotNull { it.label }
        return when {
            cfg.loggedOut.matches(labels) -> ScreenState.LOGGED_OUT
            cfg.rideInProgress.matches(labels) -> ScreenState.RIDE_IN_PROGRESS
            cfg.loading.matches(labels) -> ScreenState.LOADING
            cfg.fareList.matches(labels) -> ScreenState.FARE_LIST
            else -> ScreenState.UNKNOWN
        }
    }

    /**
     * Reads every rideable product with a price. Excluded products (rentals, parcel...) and
     * names not in the catalog are skipped. The first (topmost) occurrence of a product wins,
     * which de-duplicates lists that repeat rides under category headings.
     */
    fun parse(nodes: List<ScreenNode>, cfg: AppReaderConfig): List<Fare> {
        val byName = cfg.products.associateBy { it.name.lowercase() }
        val namesLongestFirst = cfg.products.map { it.name }.sortedByDescending { it.length }
        val out = LinkedHashMap<String, Fare>()

        // Mode A: a whole row exposed as one node ("Auto, 3 seats, 12:22 PM · 3 min, ₹49.40").
        for (n in nodes.sortedBy { it.top }) {
            for (raw in listOfNotNull(n.desc, n.text)) {
                val s = raw.trimStart()
                val name = namesLongestFirst.firstOrNull { startsWithWord(s, it) } ?: continue
                if (s.trim().equals(name, ignoreCase = true)) continue // bare name: mode B
                val spec = byName.getValue(name.lowercase())
                val price = firstPrice(s.substring(name.length), cfg) ?: continue
                add(out, spec, price, firstEta(listOf(s), cfg))
            }
        }

        // Mode B: separate nodes for name, ETA and price; group them into rows by position.
        val anchors = nodes
            .filter { n -> n.label?.trim()?.lowercase()?.let { it in byName } == true }
            .sortedBy { it.top }
        if (anchors.isEmpty()) return out.values.toList()
        val gaps = anchors.zipWithNext { a, b -> b.top - a.top }.filter { it > 0 }.sorted()
        val rowPitch = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else anchors.first().height * 4

        anchors.forEachIndexed { i, anchor ->
            val spec = byName.getValue(anchor.label!!.trim().lowercase())
            val bandTop = anchor.top - anchor.height * 6 / 10
            val next = anchors.getOrNull(i + 1)
            val bandBottom = if (next != null) next.top - next.height * 6 / 10 else anchor.top + rowPitch
            val row = nodes.filter { it !== anchor && it.centerY in bandTop until bandBottom }
            // The payable price is the topmost one in the row; a struck-through original sits below.
            val price = row
                .filter { it.left >= anchor.left }
                .sortedBy { it.top }
                .firstNotNullOfOrNull { n -> n.label?.let { firstPrice(it, cfg) } }
                ?: return@forEachIndexed
            add(out, spec, price, firstEta(row.mapNotNull { it.label }, cfg))
        }
        return out.values.toList()
    }

    private fun add(out: MutableMap<String, Fare>, spec: ProductSpec, price: Pair<Double, Double>, eta: Int?) {
        if (spec.exclude != null || spec.rideType == null) return
        if (spec.key in out) return
        out[spec.key] = Fare(spec.key, spec.rideType, price.first, price.second, eta)
    }

    private fun startsWithWord(s: String, name: String): Boolean {
        val t = s.trimStart()
        if (!t.startsWith(name, ignoreCase = true)) return false
        val after = t.getOrNull(name.length) ?: return true
        return !after.isLetterOrDigit()
    }

    fun firstPrice(s: String, cfg: AppReaderConfig): Pair<Double, Double>? {
        val m = cfg.pricePattern.find(s) ?: return null
        val lo = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val hi = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.replace(",", "")?.toDoubleOrNull() ?: lo
        if (lo <= 0 || hi < lo || hi > 20000) return null
        return lo to hi
    }

    private fun firstEta(texts: List<String>, cfg: AppReaderConfig): Int? {
        for (p in cfg.etaPatterns) for (t in texts) {
            p.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..180 }?.let { return it }
        }
        return null
    }
}
