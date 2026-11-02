package app.hail.android

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide switch for the accessibility service. The service does nothing at all
 * unless [active] is true, which only happens while a user-started request (or a debug
 * dump the user tapped) is running.
 */
object AgentState {
    @Volatile var active: Boolean = false
    @Volatile var stopRequested: Boolean = false
    @Volatile var targetPackage: String? = null

    /** Debug only, in memory only: fare-like text seen during the last check. Never sent. */
    val seenFareText = CopyOnWriteArrayList<String>()

    fun begin(pkg: String?) {
        stopRequested = false
        targetPackage = pkg
        active = true
    }

    fun end() {
        active = false
        targetPackage = null
    }
}
