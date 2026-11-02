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
 * action_request / handoff_request the backend returns, and hands anything that needs
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

    /** [apps]: ride apps connected on this phone (installed + switched on). */
    fun sendText(text: String, apps: List<String>, l: Listener) = run(
        JSONObject().put("type", "user_message").put("text", text)
            .put("available_apps", org.json.JSONArray(apps))
            .apply { Here.now(ctx)?.let { put("location", it) } }, l
    )

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
                RideApps.ensureConfig(ctx) { api.readersConfig() }
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
                        "handoff_request" -> {
                            val action = out.getJSONObject("action")
                            main.post { l.onProgress("Opening ${action.getString("app").replaceFirstChar { it.uppercase() }}…") }
                            val result = openForBooking(out.getString("action_id"), action)
                            // The user is now in the ride app to tap Book; don't pull them back.
                            launchedApps = false
                            out = api.postMessage(sessionId, result)
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

    /**
     * Debug "Test fare check": runs one app's fare check on this phone, no backend involved.
     * Pickup is the phone's location as the app sees it; the destination is searched by name.
     * The result stays on the phone (shown on the Debug screen only).
     */
    fun testCheck(app: String, destination: String, done: (String) -> Unit) {
        AgentState.stopRequested = false
        AgentService.show(ctx, "Test: checking $app…")
        exec.execute {
            val report = StringBuilder()
            try {
                RideApps.ensureConfig(ctx) { runCatching { api.readersConfig() }.getOrNull() }
                val action = JSONObject().put("app", app)
                    .put("pickup", JSONObject().put("lat", JSONObject.NULL).put("lng", JSONObject.NULL).put("label", "Current location"))
                    .put("drop", JSONObject().put("lat", JSONObject.NULL).put("lng", JSONObject.NULL).put("label", destination))
                    .put("ride_types", JSONArray(listOf("cab", "auto", "bike")))
                    .put("pickup_is_current_location", true)
                val r = checkFares("test", action, allowSimulate = false)
                report.append("Status: ").append(r.optString("status")).append('\n')
                val obs = r.optJSONArray("observations") ?: JSONArray()
                for (i in 0 until obs.length()) {
                    val o = obs.getJSONObject(i)
                    val eta = if (o.isNull("eta_minutes")) "" else " · ${o.getInt("eta_minutes")} min"
                    report.append("  ${RideApps.productName(app, o.getString("product"))}: ₹${o.getDouble("fare_min")}$eta\n")
                }
                report.append("Steps:\n").append(AgentState.seenFareText.joinToString("\n") { "  $it" })
            } catch (e: Exception) {
                report.append("Error: ${e.javaClass.simpleName}")
            } finally {
                AgentState.end()
                AgentService.hide(ctx)
                bringToFront(DebugActivity::class.java)
            }
            main.post { done(report.toString()) }
        }
    }

    private fun checkFares(actionId: String, action: JSONObject, allowSimulate: Boolean = true): JSONObject {
        val app = action.getString("app")
        val result = JSONObject().put("type", "tool_result").put("action_id", actionId)
        if (AgentState.stopRequested) return result.put("status", "stopped_by_user")
        if (allowSimulate && prefs.simulateFares) {
            return result.put("status", "ok")
                .put("observations", simulated(app, action.getJSONArray("ride_types")))
        }
        if (!RideApps.isInstalled(ctx, app)) return result.put("status", "app_not_installed")
        val svc = HailAccessibilityService.instance ?: return result.put("status", "failed")

        AgentState.seenFareText.clear()
        pickupUnverified = false
        val pkg = RideApps.packages.getValue(app)
        val cfg = RideApps.readers[app]
        AgentState.begin(pkg)
        try {
            if (cfg == null) return result.put("status", "failed") // no reader config yet
            val pickup = Place.from(action.getJSONObject("pickup"))
            val drop = Place.from(action.getJSONObject("drop"))
            val pickupIsHere = action.optBoolean("pickup_is_current_location", false)
            val goal = NavGoal(dropQuery = drop.label, pickupQuery = if (pickupIsHere) null else pickup.label)
            val reach = reachFareList(svc, app, pkg, cfg, pickup, drop, goal)
            if (reach != null) return result.put("status", reach)
            val wanted = action.getJSONArray("ride_types").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            return when (val r = readFareList(svc, pkg, cfg)) {
                is ReadResult.Fares -> result.put("status", "ok")
                    .put("observations", toObservations(app, r.fares.filter { it.rideType in wanted }, r.confidence))
                is ReadResult.Status -> result.put("status", r.status)
            }
        } finally {
            AgentState.end()
        }
    }

    /**
     * Gets the app onto the fare list for this trip. Tries the deep link first (Uber, Ola),
     * and if that doesn't show the fare list within a few seconds, navigates like a person:
     * search bar, destination, suggestion. Returns null on success, else a tool_result status.
     */
    private fun reachFareList(
        svc: HailAccessibilityService, app: String, pkg: String, cfg: AppReaderConfig,
        pickup: Place, drop: Place, goal: NavGoal,
    ): String? {
        if (RideApps.launchDeepLink(svc, app, pickup, drop)) {
            val until = System.currentTimeMillis() + DEEP_LINK_WAIT_MS
            while (System.currentTimeMillis() < until) {
                if (AgentState.stopRequested) return "stopped_by_user"
                Thread.sleep(POLL_MS)
                val nodes = svc.snapshot(pkg) ?: continue
                when (FareReader.state(nodes, cfg)) {
                    ScreenState.FARE_LIST -> return null
                    ScreenState.LOGGED_OUT -> return "not_logged_in"
                    else -> Unit
                }
            }
            debug("$app: deep link didn't reach the fare list, navigating")
        }
        if (!RideApps.launchHome(svc, app)) return "app_not_installed"
        val mem = NavMemory()
        val deadline = System.currentTimeMillis() + NAV_TIMEOUT_MS
        Thread.sleep(LAUNCH_SETTLE_MS)
        while (System.currentTimeMillis() < deadline) {
            if (AgentState.stopRequested) return "stopped_by_user"
            val snap = svc.snap(pkg)
            if (snap == null) { Thread.sleep(POLL_MS); continue }
            val step = Navigator.decide(snap.nodes, cfg, goal, mem)
            debug("$app: ${step.javaClass.simpleName} ${(step as? NavAction.Tap)?.why ?: (step as? NavAction.Type)?.why ?: (step as? NavAction.Wait)?.why ?: (step as? NavAction.Fail)?.why ?: ""}")
            when (step) {
                NavAction.Arrived -> {
                    if (mem.pickupUnverified) pickupUnverified = true
                    return null
                }
                is NavAction.Fail -> return step.status
                is NavAction.Tap -> svc.tap(snap.infos[step.index], snap.nodes[step.index].label)
                is NavAction.Type -> svc.typeInto(snap.infos[step.index], step.text)
                NavAction.Back -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                is NavAction.Wait -> Unit
            }
            Thread.sleep(if (step is NavAction.Type) TYPE_SETTLE_MS else POLL_MS)
        }
        return "failed"
    }

    /** Set when the app's pickup couldn't be changed from the current location; lowers confidence. */
    @Volatile private var pickupUnverified = false

    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) AgentState.seenFareText.add(msg)
    }

    private sealed class ReadResult {
        data class Fares(val fares: List<Fare>, val confidence: Double) : ReadResult()
        data class Status(val status: String) : ReadResult()
    }

    /**
     * Polls the ride app until its fare list is on screen and the prices stop changing
     * (they load 1-3 s after the rows), then scrolls to collect rides below the fold.
     */
    private fun readFareList(svc: HailAccessibilityService, pkg: String, cfg: AppReaderConfig): ReadResult {
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        var last: List<Fare> = emptyList()
        var stablePolls = 0
        while (System.currentTimeMillis() < deadline) {
            if (AgentState.stopRequested) return ReadResult.Status("stopped_by_user")
            Thread.sleep(POLL_MS)
            val nodes = svc.snapshot(pkg) ?: continue
            when (FareReader.state(nodes, cfg)) {
                ScreenState.LOGGED_OUT -> return ReadResult.Status("not_logged_in")
                ScreenState.FARE_LIST -> {
                    val fares = FareReader.parse(nodes, cfg)
                    debug("${cfg.app}: ${fares.size} fares read")
                    stablePolls = if (fares.isNotEmpty() && fares == last) stablePolls + 1 else 0
                    last = fares
                    if (stablePolls >= 1) {
                        // Lower confidence if the trip on screen may not be ours.
                        val conf = if (pickupUnverified) 0.6 else 0.9
                        return ReadResult.Fares(collectBelowFold(svc, pkg, cfg, fares), conf)
                    }
                }
                else -> stablePolls = 0
            }
        }
        // Timed out: report what we have, marked low-confidence, rather than nothing.
        return if (last.isNotEmpty()) ReadResult.Fares(last, 0.6) else ReadResult.Status("failed")
    }

    private fun collectBelowFold(svc: HailAccessibilityService, pkg: String, cfg: AppReaderConfig, first: List<Fare>): List<Fare> {
        val all = LinkedHashMap<String, Fare>().apply { first.forEach { put(it.productKey, it) } }
        repeat(MAX_SCROLLS) {
            if (AgentState.stopRequested || !svc.scrollForward(pkg)) return all.values.toList()
            Thread.sleep(SCROLL_SETTLE_MS)
            val nodes = svc.snapshot(pkg) ?: return all.values.toList()
            val before = all.size
            FareReader.parse(nodes, cfg).forEach { all.putIfAbsent(it.productKey, it) }
            if (all.size == before) return all.values.toList()
        }
        return all.values.toList()
    }

    private fun toObservations(app: String, fares: List<Fare>, confidence: Double): JSONArray {
        val now = Instant.now().toString()
        val out = JSONArray()
        fares.take(30).forEach { f ->
            out.put(
                JSONObject().put("app", app).put("ride_type", f.rideType).put("product", f.productKey)
                    .put("fare_min", f.fareMin).put("fare_max", f.fareMax).put("currency", "INR")
                    .put("eta_minutes", f.etaMinutes ?: JSONObject.NULL).put("surge_flag", false)
                    .put("captured_at", now).put("confidence", confidence)
                    .put("screen_signature", "$app.fare_list.draft1")
            )
        }
        return out
    }

    /**
     * The user tapped an option: open that app with the trip filled in. The user books it
     * there. Hail never taps Book.
     */
    private fun openForBooking(actionId: String, action: JSONObject): JSONObject {
        val app = action.getString("app")
        val pickup = Place.from(action.getJSONObject("pickup"))
        val drop = Place.from(action.getJSONObject("drop"))
        val svc = HailAccessibilityService.instance
        val pkg = RideApps.packages[app]
        val cfg = RideApps.readers[app]
        var opened = false
        if (svc != null && pkg != null && cfg != null) {
            // Same route as the fare check, so the user lands on this trip's ride list and only
            // has to pick the ride type and tap Book. Hail stops there.
            val here = action.optBoolean("pickup_is_current_location", false)
            AgentState.begin(pkg)
            try {
                opened = reachFareList(svc, app, pkg, cfg, pickup, drop, NavGoal(drop.label, if (here) null else pickup.label)) == null
            } finally {
                AgentState.end()
            }
        }
        if (!opened) opened = RideApps.launch(svc ?: ctx, app, pickup, drop)
        return JSONObject().put("type", "tool_result").put("action_id", actionId)
            .put("status", if (opened) "ok" else "failed")
            .put("handoff", JSONObject().put("opened", opened))
    }

    private fun bringToFront(screen: Class<*> = MainActivity::class.java) {
        val i = Intent(ctx, screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { (HailAccessibilityService.instance ?: ctx).startActivity(i) }
    }

    /** Debug "simulate" mode: real fares from the founder's recordings (Bangalore, 2 Nov). */
    private fun simulated(app: String, rideTypes: JSONArray): JSONArray {
        val wanted = (0 until rideTypes.length()).map { rideTypes.getString(it) }.toSet()
        return toObservations(app, SIMULATED[app].orEmpty().filter { it.rideType in wanted }, 0.9)
    }

    companion object {
        private const val READ_TIMEOUT_MS = 15_000L
        private const val DEEP_LINK_WAIT_MS = 8_000L
        private const val NAV_TIMEOUT_MS = 35_000L
        private const val LAUNCH_SETTLE_MS = 1_500L
        private const val TYPE_SETTLE_MS = 1_500L
        private const val POLL_MS = 700L
        private const val SCROLL_SETTLE_MS = 600L
        private const val MAX_SCROLLS = 4
        private fun newId() = UUID.randomUUID().toString().replace("-", "")

        private fun f(key: String, type: String, fare: Double, eta: Int) = Fare(key, type, fare, fare, eta)
        private val SIMULATED = mapOf(
            "uber" to listOf(f("uber_auto", "auto", 49.40, 3), f("uber_bike_saver", "bike", 40.96, 6),
                f("uber_go_non_ac", "cab", 109.15, 7), f("uber_go_ac", "cab", 118.75, 6),
                f("uber_premier_ac", "cab", 64.98, 5)),
            "ola" to listOf(f("ola_auto", "auto", 46.0, 1), f("ola_bike", "bike", 40.0, 1),
                f("ola_mini_non_ac", "cab", 112.0, 1), f("ola_mini", "cab", 118.0, 4),
                f("ola_prime_sedan", "cab", 124.0, 3)),
            "rapido" to listOf(f("rapido_auto", "auto", 49.0, 3), f("rapido_bike_direct", "bike", 40.0, 3),
                f("rapido_cab_non_ac", "cab", 101.0, 3), f("rapido_cab_ac", "cab", 108.0, 3),
                f("rapido_cab_premium", "cab", 132.0, 3)),
        )
    }
}
