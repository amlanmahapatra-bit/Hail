package app.hail.android

/** The ride types the user picks from. Same words as the backend's app/categories.py. */
enum class RideCategory(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    BIKE("bike", "Bike"),
    CAB("cab", "Cab"),
    PREMIUM("premium", "Premium"),
    CAB_XL("cab_xl", "Cab XL");

    companion object {
        fun of(key: String?): RideCategory? = entries.firstOrNull { it.key == key }

        private val PATTERNS = listOf(
            // XL first: "big cab" is not plain "cab".
            CAB_XL to Regex("\\bxl\\b|\\bsuv\\b|\\bbig(?:ger)? (?:cab|car|gaadi)\\b|\\blarge (?:cab|car)\\b|\\b(?:6|six)[ -]?seater\\b|\\binnova\\b|\\bxuv\\b|\\bbadi gaadi\\b"),
            // Premium before plain cab: "sedan", "premium cab".
            PREMIUM to Regex("\\bpremium\\b|\\bsedan\\b|\\bluxury\\b|\\bblack\\b|\\bprime\\b|\\bpremier\\b"),
            AUTO to Regex("\\bauto\\b|\\brick?shaw\\b|\\briksha\\b|\\brikshaw\\b|\\btuk ?tuk\\b"),
            BIKE to Regex("\\bbike\\b|\\bscooty\\b|\\bscooter\\b|\\btwo[ -]?wheeler\\b|\\bmoto\\b"),
            CAB to Regex("\\bcab\\b|\\bcar\\b|\\btaxi\\b|\\bgaadi\\b|\\bgadi\\b"),
        )

        /** "rickshaw" -> AUTO, "big cab" -> CAB_XL, anything else -> null. */
        fun detect(text: String): RideCategory? {
            val t = text.lowercase()
            return PATTERNS.firstOrNull { it.second.containsMatchIn(t) }?.first
        }
    }
}
