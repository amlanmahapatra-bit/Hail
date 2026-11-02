package app.hail.android

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Drives one session against the backend: sends the user's message, executes every
 * action_request / booking_request the backend returns, and hands anything that needs
 * the user (assistant_message, recommendation, error) back to the UI.
 */
class AgentRunner(private val ctx: Context, private val prefs: Prefs) {
    interface Listener {
        fun onResponse(out: JSONObject)
        fun onProgress(text: String)
        fun onFailure(text: String)
    }

    private val api = Api(prefs)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var sessionId: String = newId()
        private set

    fun newSession() {
        sessionId = newId()
    }

    fun sendText(text: String, l: Listener) =
        run(JSONObject().put("type", "user_message").put("text", text), l)

    fun confirm(optionId: String, token: String, accept: Boolean, l: Listener) = run(
        JSONObject().put("type", "confirmation").put("option_id", optionId)
            .put("token", token).put("accept", accept), l
    )

    fun deleteMyData(done: (Boolean) -> Unit) = exec.execute {
        val ok = runCatching { api.deleteMyData() }.isSuccess
        main.post { done(ok) }
    }

    private fun run(first: JSONObject, l: Listener) {
        AgentState.stopRequested = false
        AgentService.show(ctx, "Working on your request…")
        exec.execute {
            var launchedApps = false
            try {
                if (RideApps.config == null) {
                    RideApps.config = runCatching { api.readersConfig() }.getOrNull()
                }
                var out = api.postMessage(sessionId, first)
                while (true) {
                    when (out.optString("type")) {
                        "action_request" -> {
                            val action = out.getJSONObject("action")
                            val app = action.getString("app")
                            main.post { l.onProgress("Checking ${app.replaceFirstChar { it.uppercase() }}…") }
                            AgentService.show(ctx, "Checking fares in $app…")
                            val result = checkFares(out.getString("action_id"), action)
                            if (!prefs.simulateFares) launchedApps = true
                            out = api.postMessage(sessionId, result)
                        }
                        "booking_request" -> {
                            main.post { l.onProgress("Booking…") }
                            out = api.postMessage(sessionId, book(out.getString("action_id")))
                        }
                        else -> {
                            val final = out
                            main.post { l.onResponse(final) }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { l.onFailure("Can't reach Hail (${e.message ?: e.javaClass.simpleName}).") }
            } finally {
                AgentState.end()
                AgentService.hide(ctx)
                if (launchedApps) bringToFront()
            }
        }
    }

    private fun checkFares(actionId: String, action: JSONObject): JSONObject {
        val app = action.getString("app")
        val result = JSONObject().put("type", "tool_result").put("action_id", actionId)
        if (AgentState.stopRequested) return result.put("status", "stopped_by_user")
        if (prefs.simulateFares) {
            return result.put("status", "ok")
                .put("observations", simulated(app, action.getJSONArray("ride_types")))
        }
        if (!RideApps.isInstalled(ctx, app)) return result.put("status", "app_not_installed")
        val svc = HailAccessibilityService.instance ?: return result.put("status", "failed")

        AgentState.seenFareText.clear()
        AgentState.begin(RideApps.packages[app])
        try {
            val launched = RideApps.launch(
                svc, app,
                Place.from(action.getJSONObject("pickup")), Place.from(action.getJSONObject("drop"))
            )
            if (!launched) return result.put("status", "failed")
            // Week 1: watch the screen for a while and log fare text (debug builds).
            // Parsing into observations comes after we have real dumps.
            val until = System.currentTimeMillis() + WATCH_MS
            while (System.currentTimeMillis() < until) {
                if (AgentState.stopRequested) return result.put("status", "stopped_by_user")
                Thread.sleep(250)
            }
        } finally {
            AgentState.end()
        }
        return result.put("status", "failed").put("observations", JSONArray())
    }

    /** Week 1: the booking tapper is not built yet, so nothing is ever booked. */
    private fun book(actionId: String): JSONObject = JSONObject()
        .put("type", "tool_result").put("action_id", actionId)
        .put("status", if (AgentState.stopRequested) "stopped_by_user" else "failed")

    private fun bringToFront() {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { (HailAccessibilityService.instance ?: ctx).startActivity(i) }
    }

    private fun simulated(app: String, rideTypes: JSONArray): JSONArray {
        val wanted = (0 until rideTypes.length()).map { rideTypes.getString(it) }.toSet()
        val now = Instant.now().toString()
        val out = JSONArray()
        SIMULATED[app].orEmpty().filter { it.first in wanted }.forEach { (rt, lo, hi, eta) ->
            out.put(
                JSONObject().put("app", app).put("ride_type", rt)
                    .put("fare_min", lo).put("fare_max", hi).put("currency", "INR")
                    .put("eta_minutes", eta).put("surge_flag", false).put("captured_at", now)
                    .put("confidence", 0.9).put("screen_signature", "simulated.v1")
            )
        }
        return out
    }

    private data class Fare(val first: String, val lo: Int, val hi: Int, val eta: Int)

    companion object {
        private const val WATCH_MS = 10_000L
        private fun newId() = UUID.randomUUID().toString().replace("-", "")

        // Made-up test fares (same as tools/fake_phone.py). Debug "simulate" mode only.
        private val SIMULATED = mapOf(
            "uber" to listOf(Fare("cab", 310, 340, 6), Fare("auto", 180, 190, 4), Fare("bike", 95, 105, 3)),
            "ola" to listOf(Fare("cab", 290, 320, 8), Fare("auto", 170, 185, 7)),
            "rapido" to listOf(Fare("cab", 270, 300, 9), Fare("auto", 150, 160, 5), Fare("bike", 80, 90, 2)),
        )
    }
}
