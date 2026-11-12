package app.hail.android

/**
 * Gets a ride app from wherever it is to the fare list for our trip, the way a person does:
 * tap the search bar, type the destination, tap the matching suggestion. Used when the app has
 * no working deep link (Rapido), or the deep link didn't land on the fare list.
 *
 * Pure decision logic (screen in, one action out), unit-tested against screens from the
 * founder's recordings. The accessibility service executes the action.
 *
 * SAFETY: Hail never books. [NEVER_TAP] blocks anything that looks like a booking, payment or
 * cancel button, here and again in the executor.
 */

data class NavConfig(
    val searchBar: List<String>,
    val dismiss: List<String>,
    /**
     * Labels of a destination field that the app does not mark as editable (Rapido's Compose
     * search screen exposes it only as "Enter Drop location Input Field..."). Matched as
     * case-insensitive substrings; Hail types into it, then into whatever field takes focus.
     */
    val dropField: List<String> = emptyList(),
)

data class NavGoal(
    val dropQuery: String,
    /** null = pickup is the phone's current location, which every app defaults to. */
    val pickupQuery: String?,
)

class NavMemory {
    var steps = 0
    var pickupDone = false
    var typingPickup = false
    var pickupUnverified = false
    /** Typed the destination into a labelled (non-editable) field; now waiting for suggestions. */
    var typedLabelledField = false
    /** The recent-destination shortcut was tried once; after that, always search. */
    var recentTried = false
    var lastAction: String? = null
    var repeats = 0
}

sealed class NavAction {
    data class Tap(val index: Int, val why: String) : NavAction()
    data class Type(val index: Int, val text: String, val why: String) : NavAction()
    data class Wait(val why: String) : NavAction()
    object Back : NavAction()
    object Arrived : NavAction()
    data class Fail(val status: String, val why: String) : NavAction()
}

object Navigator {
    const val MAX_STEPS = 24

    /** Buttons Hail must never press: it only reads fares and opens the app. */
    val NEVER_TAP = Regex("^\\s*(book|confirm|request|choose|pay|cancel|change)\\b", RegexOption.IGNORE_CASE)

    private val NOT_A_SUGGESTION = Regex(
        "locate on map|set location on map|choose on map|current location|saved places|add stop",
        RegexOption.IGNORE_CASE,
    )

    fun decide(nodes: List<ScreenNode>, cfg: AppReaderConfig, goal: NavGoal, mem: NavMemory): NavAction {
        mem.steps++
        if (mem.steps > MAX_STEPS) return NavAction.Fail("failed", "too many steps")
        val action = decideInner(nodes, cfg, goal, mem)
        val sig = action.toString()
        mem.repeats = if (sig == mem.lastAction && action !is NavAction.Wait) mem.repeats + 1 else 0
        mem.lastAction = sig
        if (mem.repeats >= 3) return NavAction.Fail("failed", "stuck repeating $sig")
        if (action is NavAction.Tap && nodes[action.index].label?.let { NEVER_TAP.containsMatchIn(it) } == true) {
            return NavAction.Fail("failed", "refused to tap a booking/payment button")
        }
        return action
    }

    private fun decideInner(nodes: List<ScreenNode>, cfg: AppReaderConfig, goal: NavGoal, mem: NavMemory): NavAction {
        when (FareReader.state(nodes, cfg)) {
            ScreenState.FARE_LIST -> return NavAction.Arrived
            ScreenState.LOGGED_OUT -> return NavAction.Fail("not_logged_in", "login screen")
            ScreenState.RIDE_IN_PROGRESS -> return NavAction.Fail("failed", "a ride is already in progress")
            ScreenState.LOADING -> return NavAction.Wait("loading")
            ScreenState.UNKNOWN -> Unit
        }
        val idx = nodes.indices
        val fields = idx.filter { nodes[it].editable }.sortedBy { nodes[it].top }

        if (fields.isNotEmpty()) {
            // Search screen. Set the pickup first if it isn't the current location and the screen
            // has two fields (pickup above drop); otherwise type into the destination field.
            val wantPickup = goal.pickupQuery != null && !mem.pickupDone
            if (wantPickup && fields.size < 2) mem.pickupUnverified = true
            val typingPickup = wantPickup && fields.size >= 2
            // Pickup sits above the destination on every app's search screen.
            val field = if (typingPickup) fields.first() else fields.last()
            val query = if (typingPickup) goal.pickupQuery!! else goal.dropQuery
            val current = nodes[field].text.orEmpty()
            if (!normalize(current).contains(normalize(query))) {
                mem.typingPickup = typingPickup
                return NavAction.Type(field, query, if (typingPickup) "type pickup" else "type destination")
            }
            val pick = bestMatch(nodes, query, below = nodes[field].bottom, minScore = 0.67)
                ?: return NavAction.Wait("waiting for suggestions")
            if (mem.typingPickup) {
                mem.pickupDone = true
                mem.typingPickup = false
            }
            return NavAction.Tap(pick, "pick suggestion")
        }

        // Search screen whose destination field isn't exposed as editable: find it by its label,
        // type once, then tap the matching suggestion below it.
        idx.firstOrNull { i -> nodes[i].label?.let { l -> cfg.nav.dropField.any { l.contains(it, true) } } == true }
            ?.let { field ->
                if (!mem.typedLabelledField) {
                    mem.typedLabelledField = true
                    return NavAction.Type(field, goal.dropQuery, "type destination (labelled field)")
                }
                val pick = bestMatch(nodes, goal.dropQuery, below = nodes[field].bottom, minScore = 0.67)
                    ?: return NavAction.Wait("waiting for suggestions")
                return NavAction.Tap(pick, "pick suggestion")
            }

        // Home screen. A recent destination that matches exactly is the quickest route, but only
        // when pickup is the current location (recents keep the app's default pickup).
        // Tried once only: if the app didn't respond, search instead of tapping it again.
        if (goal.pickupQuery == null && !mem.recentTried) {
            bestMatch(nodes, goal.dropQuery, below = Int.MIN_VALUE, minScore = 1.0)?.let {
                mem.recentTried = true
                return NavAction.Tap(it, "recent destination")
            }
        }
        idx.firstOrNull { i -> nodes[i].label?.let { l -> cfg.nav.searchBar.any { l.trim().equals(it, true) } } == true }
            ?.let { return NavAction.Tap(it, "open search") }
        idx.firstOrNull { i -> nodes[i].label?.let { l -> cfg.nav.dismiss.any { l.trim().equals(it, true) } } == true }
            ?.let { return NavAction.Tap(it, "dismiss popup") }
        return NavAction.Wait("unknown screen")
    }

    /** Index of the node whose text best covers the query's words, top-most on ties. */
    fun bestMatch(nodes: List<ScreenNode>, query: String, below: Int, minScore: Double): Int? {
        val want = tokens(query)
        if (want.isEmpty()) return null
        var best: Int? = null
        var bestScore = 0.0
        for (i in nodes.indices.sortedBy { nodes[it].top }) {
            val n = nodes[i]
            val label = n.label ?: continue
            if (n.editable || n.top < below || NOT_A_SUGGESTION.containsMatchIn(label) || NEVER_TAP.containsMatchIn(label)) continue
            val have = tokens(label)
            val score = want.count { it in have }.toDouble() / want.size
            if (score >= minScore && score > bestScore) {
                best = i
                bestScore = score
            }
        }
        return best
    }

    private val STOP = setOf("the", "of", "and", "near", "opp", "opposite", "to", "in")

    fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP }.toSet()

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
