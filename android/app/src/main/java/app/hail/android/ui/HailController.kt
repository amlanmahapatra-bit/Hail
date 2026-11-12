package app.hail.android.ui

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hail.android.AgentRunner
import app.hail.android.Analytics
import app.hail.android.Pick
import app.hail.android.Prefs
import app.hail.android.RideApps
import app.hail.android.RideCategory
import app.hail.android.SpokenOption
import app.hail.android.Voice
import app.hail.android.VoicePick
import org.json.JSONArray
import org.json.JSONObject

/** Where a ride request is. The Home screen shows the trip screens on top while not Idle. */
sealed interface Phase {
    data object Idle : Phase
    data class Listening(val partial: String) : Phase
    data class Checking(val to: String, val category: String?, val apps: List<AppCheck>) : Phase
    data class Asking(val question: String, val choices: List<RideCategory>) : Phase
    data class Reply(val text: String) : Phase
    data class Results(val rec: Recommendation, val to: String) : Phase
    data class Opening(val app: String) : Phase
    data class Done(
        val app: String, val product: String, val text: String, val saved: Int?, val live: Boolean,
        val noSavingReason: String? = null,
    ) : Phase
    data class Failed(val text: String) : Phase
}

enum class CheckState { WAITING, READING, DONE }
data class AppCheck(val app: String, val state: CheckState)

/**
 * The app's state, driven by [AgentRunner]: what the user asked, which app is being read, the
 * options card, and the result. The agent and the confirmation gate are unchanged; this only
 * turns their messages into screens.
 */
class HailController(
    private val activity: Activity,
    val prefs: Prefs,
    private val runner: AgentRunner,
    private val voice: Voice,
    val trips: TripLog,
) : AgentRunner.Listener {
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var category by mutableStateOf(prefs.rideCategory)
        private set
    val favorites = mutableStateListOf<JSONObject>()
    var tripsVersion by mutableStateOf(0)
        private set

    private var to = ""
    private var chosen: RideOption? = null
    private var lastRec: Recommendation? = null
    private var voiceMode = false
    private var pickTries = 0

    init {
        loadFavorites(prefs.favoritesCache)
        refreshFavorites()
    }

    val busy get() = phase is Phase.Checking || phase is Phase.Opening || phase is Phase.Listening

    // ------------------------------------------------------------------ requests

    fun chooseCategory(key: String?) {
        category = key
        prefs.rideCategory = key
    }

    /** Typed or spoken request: "Koramangala, cheapest", "to MG Road", "jaana hai sasta wala". */
    fun ask(text: String, spoken: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        voiceMode = spoken
        val cat = RideCategory.detect(t)?.key ?: category
        if (cat != null && cat != category) chooseCategory(cat)
        if (phase !is Phase.Reply && phase !is Phase.Asking) {
            runner.newSession()
            to = destinationOf(t)
        }
        send(t, spoken, cat, null)
    }

    fun goTo(fav: JSONObject) {
        if (busy) return
        voiceMode = false
        runner.newSession()
        val label = fav.getString("label")
        to = label
        val dest = JSONObject().put("label", label)
            .put("lat", if (fav.isNull("lat")) JSONObject.NULL else fav.getDouble("lat"))
            .put("lng", if (fav.isNull("lng")) JSONObject.NULL else fav.getDouble("lng"))
        send("to $label", false, category, dest)
    }

    fun answerCategory(c: RideCategory) {
        voice.stopListening(); voice.stopSpeaking()
        chooseCategory(c.key)
        send(c.label, voiceMode, c.key, null)
    }

    private fun send(t: String, spoken: Boolean, cat: String?, dest: JSONObject?) {
        val apps = if (prefs.simulateFares) RideApps.packages.keys.toList() else RideApps.connected(activity, prefs)
        phase = Phase.Checking(to.ifBlank { "your destination" }, cat, apps.map { AppCheck(it, CheckState.WAITING) })
        Analytics.event(activity, "request_started", "input" to if (spoken) "voice" else "text", "category" to (cat ?: "ask"))
        runner.sendText(t, apps, this, spoken, cat, dest)
    }

    fun pick(o: RideOption, via: String = "tap") {
        val rec = (phase as? Phase.Results)?.rec ?: return
        voice.stopListening(); voice.stopSpeaking()
        chosen = o
        phase = Phase.Opening(o.appName)
        runner.confirm(o.id, rec.token, true, this, via = via)
    }

    fun noneOfThese() {
        val rec = (phase as? Phase.Results)?.rec ?: return
        voice.stopListening(); voice.stopSpeaking()
        chosen = null
        runner.confirm(rec.bestId, rec.token, false, this)
        phase = Phase.Idle
    }

    fun close() {
        voice.stopListening(); voice.stopSpeaking()
        app.hail.android.AgentState.stopRequested = true
        phase = Phase.Idle
    }

    // ------------------------------------------------------------------ voice

    fun startVoice() {
        if (busy) return
        if (!voice.canListen()) { phase = Phase.Failed("Voice isn't available on this phone. Type instead."); return }
        val continuing = phase is Phase.Reply || phase is Phase.Asking
        val before = phase
        phase = Phase.Listening("")
        voice.listen(
            onPartial = { phase = Phase.Listening(it) },
            onResult = { heard ->
                if (heard.isNullOrBlank()) { phase = Phase.Failed("Didn't catch that. Tap the mic to try again."); return@listen }
                phase = if (continuing) before else Phase.Idle
                ask(heard, spoken = true)
            },
        )
    }

    private fun speakOptionsAndListen(rec: Recommendation) {
        val top = rec.options.take(3).joinToString(". ") { o ->
            "${o.title}, ${Voice.rupees(o.fareMin)}" + (o.eta?.let { ", $it minutes away" } ?: "")
        }
        val choices = rec.options.map { SpokenOption(it.id, it.app, it.product, it.rideType) }
        fun listenForPick() {
            voice.listen(onPartial = {}, onResult = { heard ->
                if (phase !is Phase.Results) return@listen
                when (val p = VoicePick.match(heard.orEmpty(), choices, rec.bestId)) {
                    is Pick.Choose -> rec.options.firstOrNull { it.id == p.id }?.let {
                        voice.speak("Opening ${it.title}. Tap Book there.")
                        pick(it, via = "voice")
                    }
                    Pick.Decline -> noneOfThese()
                    Pick.Unclear -> if (++pickTries < 3) {
                        voice.speak("Sorry, which ride? Say something like Ola auto, or tap it.") { listenForPick() }
                    }
                }
            })
        }
        pickTries = 0
        voice.speak("${rec.text} Top options: $top. Which one?") { listenForPick() }
    }

    // ------------------------------------------------------------------ AgentRunner.Listener

    override fun onProgress(text: String) {
        val p = phase
        when {
            text.startsWith("Checking ") && p is Phase.Checking -> {
                val app = text.removePrefix("Checking ").trimEnd('…', '.').lowercase()
                val apps = p.apps.map {
                    when {
                        it.app == app -> it.copy(state = CheckState.READING)
                        it.state == CheckState.READING -> it.copy(state = CheckState.DONE)
                        else -> it
                    }
                }.let { list -> if (list.none { it.app == app }) list + AppCheck(app, CheckState.READING) else list }
                phase = p.copy(apps = apps)
                if (voiceMode && apps.count { it.state != CheckState.WAITING } == 1) voice.speak("Checking your ride apps.")
            }
            text.startsWith("Opening ") -> phase = Phase.Opening(text.removePrefix("Opening ").trimEnd('…', '.'))
        }
    }

    override fun onFailure(text: String) {
        phase = Phase.Failed(text)
        if (voiceMode) voice.speak(text)
    }

    override fun onResponse(out: JSONObject) {
        when (out.optString("type")) {
            "recommendation" -> {
                val rec = Recommendation.from(out)
                lastRec = rec
                rec.drop?.optString("label")?.takeIf { it.isNotBlank() && it != "Current location" }?.let { to = it }
                phase = Phase.Results(rec, to)
                Analytics.event(activity, "options_shown", "count" to rec.options.size.toString(),
                    "category" to (rec.category ?: "any"), "live" to rec.live.toString())
                if (voiceMode) speakOptionsAndListen(rec)
            }
            "assistant_message" -> {
                val text = out.optString("text")
                val ask = out.optJSONObject("ask")
                when {
                    ask != null -> {
                        val ch = ask.getJSONArray("choices")
                        phase = Phase.Asking(text, (0 until ch.length()).mapNotNull { RideCategory.of(ch.getString(it)) })
                        if (voiceMode) voice.speak(text) { listenForCategory(0) }
                    }
                    out.optBoolean("done") -> finishTrip(out)
                    else -> {
                        phase = Phase.Reply(text)
                        if (voiceMode) voice.speak(text)
                    }
                }
            }
            "error" -> {
                phase = Phase.Failed(out.optString("message").ifBlank { "Something went wrong. Try again." })
                if (voiceMode) voice.speak(out.optString("message"))
            }
            else -> phase = Phase.Failed("Something went wrong. Try again.")
        }
    }

    private fun listenForCategory(tries: Int) {
        voice.listen(onPartial = {}, onResult = { heard ->
            if (phase !is Phase.Asking) return@listen
            val c = heard?.let { RideCategory.detect(it) }
            when {
                c != null -> answerCategory(c)
                tries < 2 -> voice.speak("Sorry, say auto, bike, cab, premium or cab XL.") { listenForCategory(tries + 1) }
            }
        })
    }

    private fun finishTrip(out: JSONObject) {
        val o = chosen
        val rec = lastRec
        val saved = out.optJSONObject("savings")?.let { if (it.isNull("amount")) null else it.optInt("amount") }
        val live = rec?.live ?: true
        val reason = out.optString("no_saving_reason").takeIf { it.isNotBlank() && it != "null" }
        if (o != null) {
            trips.add(Trip(System.currentTimeMillis(), to, o.app, o.product, o.fare, saved, live, reason))
            tripsVersion++
            Analytics.event(activity, "ride_opened", "app" to o.app, "category" to (rec?.category ?: "any"),
                "saved_bucket" to bucket(saved), "live" to live.toString())
        }
        phase = Phase.Done(o?.appName ?: "the app", o?.product ?: "", out.optString("text"), saved, live, reason)
        if (voiceMode) voice.speak(out.optString("text"))
    }

    private fun bucket(s: Int?) = when {
        s == null || s <= 0 -> "0"
        s < 10 -> "1-9"
        s < 30 -> "10-29"
        s < 100 -> "30-99"
        else -> "100+"
    }

    // ------------------------------------------------------------------ favourites

    private fun loadFavorites(json: String) {
        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        favorites.clear()
        for (i in 0 until arr.length()) favorites += arr.getJSONObject(i)
    }

    fun refreshFavorites() = runner.favorites({ it.favorites() }) { arr ->
        if (arr != null) { prefs.favoritesCache = arr.toString(); loadFavorites(arr.toString()) }
    }

    fun isFavorite(label: String) = favorites.any { it.getString("label").equals(label, ignoreCase = true) }

    fun toggleFavorite(drop: JSONObject) {
        val label = drop.optString("label")
        val existing = favorites.firstOrNull { it.getString("label").equals(label, ignoreCase = true) }
        if (existing != null) {
            favorites.remove(existing)
            runner.favorites({ it.removeFavorite(existing.getInt("id")) }) { refreshFavorites() }
        } else {
            val lat = if (drop.isNull("lat")) null else drop.optDouble("lat")
            val lng = if (drop.isNull("lng")) null else drop.optDouble("lng")
            runner.favorites({ it.addFavorite(label, lat, lng) }) { fav ->
                if (fav != null) { favorites += fav; prefs.favoritesCache = JSONArray(favorites.toList()).toString() }
            }
        }
    }

    fun removeFavorite(f: JSONObject) {
        favorites.remove(f)
        runner.favorites({ it.removeFavorite(f.getInt("id")) }) { refreshFavorites() }
    }

    fun deleteMyData(done: (Boolean) -> Unit) = runner.deleteMyData { ok ->
        if (ok) { trips.clear(); tripsVersion++; favorites.clear() }
        done(ok)
    }

    companion object {
        /** "Forum Mall to Koramangala, cheapest" -> "Koramangala"; best effort, for the header only. */
        fun destinationOf(t: String): String {
            val s = t.trim()
            val m = Regex("(?i)\\bto\\s+(.+)").find(s)?.groupValues?.get(1) ?: s
            return m.split(",", " jaana", " jana", " cheapest", " fastest", " sasta", " please")
                .first().trim().replaceFirstChar { it.uppercase() }.take(40)
        }
    }
}
