package app.hail.android

/**
 * Maps what the user SAID after hearing the options to one option on the card, on the phone,
 * with fixed rules (no AI). A spoken pick counts like a tap only when it clearly names one
 * option; anything unclear makes Hail ask again. Hail never books either way: a pick only opens
 * that app on the trip, and the user taps Book there.
 *
 * Understands English and common Hinglish: "Ola auto", "Uber Go AC", "the first one",
 * "doosra", "haan" / "yes" (= the highlighted option), "nahi" / "none" (= none of these).
 */

data class SpokenOption(val id: String, val app: String, val product: String, val rideType: String)

sealed class Pick {
    data class Choose(val id: String) : Pick()
    object Decline : Pick()
    object Unclear : Pick()
}

object VoicePick {
    private val APPS = setOf("uber", "ola", "rapido")
    private val RIDE_WORDS = mapOf(
        "auto" to "auto", "rickshaw" to "auto", "riksha" to "auto",
        "bike" to "bike", "scooty" to "bike", "scooter" to "bike",
        "cab" to "cab", "car" to "cab", "taxi" to "cab",
    )
    private val THIS_ONE = listOf("that one", "this one", "the recommended", "the best", "best one", "wahi", "yehi", "yahi")
    private val AFFIRM = setOf(
        "yes", "yeah", "yep", "ok", "okay", "sure", "haan", "han", "ha", "theek", "thik", "chalo",
        "recommended", "best", "cheapest", "fastest", "book",
    )
    private val DECLINE = setOf("cancel", "none", "no", "nahi", "nahin", "nai", "stop", "neither", "mat")
    private val FILLER = setOf(
        "the", "a", "an", "it", "of", "these", "those", "for", "me", "i", "want", "please", "pls", "plz",
        "ride", "option", "wala", "wali", "wale", "chahiye", "de", "do", "karo", "kar", "lo", "le", "lena",
        "se", "bhai", "bro", "one", "with", "take", "get", "book", "let", "lets", "let's",
    )
    private val ORDINALS = listOf(
        setOf("first", "1st", "pehla", "pehli", "pahla", "pahli", "one") to 0,
        setOf("second", "2nd", "doosra", "doosri", "dusra", "dusri", "two") to 1,
        setOf("third", "3rd", "teesra", "teesri", "tisra", "three") to 2,
    )

    fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun match(transcript: String, options: List<SpokenOption>, recommendedId: String): Pick {
        if (options.isEmpty()) return Pick.Unclear
        val text = transcript.lowercase()
        val words = tokens(transcript)
        if (words.isEmpty()) return Pick.Unclear

        if (THIS_ONE.any { text.contains(it) }) return Pick.Choose(recommendedId)

        // Named ride: app and/or ride type and/or product words ("Ola auto", "Uber Go AC").
        val app = words.firstOrNull { it in APPS }
        val ride = words.firstNotNullOfOrNull { RIDE_WORDS[it] }
        val ordinalWords = ORDINALS.flatMap { it.first }.toSet()
        val productWords = words.filter {
            it !in APPS && it !in RIDE_WORDS && it !in AFFIRM && it !in DECLINE && it !in FILLER && it !in ordinalWords
        }.toSet()
        var candidates = options
        if (app != null) candidates = candidates.filter { it.app == app }
        if (ride != null) candidates = candidates.filter { it.rideType == ride }
        if (app != null || ride != null || productWords.isNotEmpty()) {
            val scored = candidates.map { o ->
                val own = tokens(o.product).filter { it !in APPS }.toSet()
                o to if (own.isEmpty()) 0.0 else own.count { it in productWords }.toDouble() / own.size
            }
            val best = scored.maxByOrNull { it.second }
            if (best != null && best.second > 0.0) {
                // Unique best product match (ties resolved by list order = best-first).
                return Pick.Choose(scored.first { it.second == best.second }.first.id)
            }
            if (app != null || ride != null) {
                // Named a ride we can't find on the card ("Ola Prime SUV"): ask, never guess.
                if (productWords.isNotEmpty() || candidates.isEmpty()) return Pick.Unclear
                return Pick.Choose(candidates.first().id) // "Ola" / "auto" -> its best option
            }
        }

        for ((set, i) in ORDINALS) if (words.any { it in set } && i < options.size) return Pick.Choose(options[i].id)
        if (words.any { it in DECLINE }) return Pick.Decline
        if (words.any { it in AFFIRM }) return Pick.Choose(recommendedId)
        return Pick.Unclear
    }
}
