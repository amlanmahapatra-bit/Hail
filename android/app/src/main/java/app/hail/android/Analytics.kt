package app.hail.android

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Firebase Analytics, limited on purpose. Events carry only coarse facts: which ride type, how
 * many options, which app was opened, a savings bucket. Never places, coordinates, fares, screen
 * text, voice, contacts or the invite code. Advertising ID collection is off (AndroidManifest).
 *
 * Does nothing until the app is built with a Firebase config (app/google-services.json), so
 * local and CI builds without one still work.
 */
object Analytics {
    private val ALLOWED_KEYS = setOf("input", "category", "count", "live", "app", "saved_bucket", "step")

    private fun fa(ctx: Context): FirebaseAnalytics? = runCatching {
        if (FirebaseApp.getApps(ctx).isEmpty()) null else FirebaseAnalytics.getInstance(ctx)
    }.getOrNull()

    fun event(ctx: Context, name: String, vararg params: Pair<String, String>) {
        val a = fa(ctx) ?: return
        val b = Bundle()
        for ((k, v) in params) if (k in ALLOWED_KEYS) b.putString(k, v.take(40))
        runCatching { a.logEvent(name, b) }
    }

    fun screen(ctx: Context, name: String) = event(ctx, FirebaseAnalytics.Event.SCREEN_VIEW, "step" to name)

    /** Delete my data: stop collecting and reset the app-instance ID. */
    fun reset(ctx: Context) {
        val a = fa(ctx) ?: return
        runCatching { a.resetAnalyticsData() }
    }
}
