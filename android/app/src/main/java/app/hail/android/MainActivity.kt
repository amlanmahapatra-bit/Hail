package app.hail.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** The bare chat screen. */
class MainActivity : Activity(), AgentRunner.Listener {
    private lateinit var prefs: Prefs
    private lateinit var runner: AgentRunner
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var banner: TextView
    private var busy = false
    private lateinit var voice: Voice
    /** True while the current request was started by voice: replies are spoken too. */
    private var voiceMode = false
    private var pickTries = 0
    private var progressSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        runner = AgentRunner(applicationContext, prefs)
        voice = Voice(this)

        val root = column()
        val top = row()
        top.addView(button("New") { runner.newSession(); log.removeAllViews(); say("New request.") })
        top.addView(button("Apps") { startActivity(Intent(this, AppsActivity::class.java)) })
        top.addView(button("Debug") { startActivity(Intent(this, DebugActivity::class.java)) })
        root.addView(top)
        banner = text("", 14f).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ConsentActivity::class.java)) }
        }
        root.addView(banner)

        log = column()
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = row()
        input = EditText(this).apply {
            hint = "e.g. to Koramangala, cheapest"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(button("Send") { send() })
        bottom.addView(button("🎤") { startVoice() })
        root.addView(bottom)
        setContentView(root)

        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        // Location: only for "pickup = where I am", read when you send a request.
        if (!Here.granted(this)) wanted += listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        say("Where to? Tap 🎤 and say \"Koramangala, cheapest\", or type \"to Koramangala, cheapest\".")
        handleVoiceShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceShortcut(intent)
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ voice

    /** Long-press the Hail icon → "Speak to Hail" opens straight into listening. */
    private fun handleVoiceShortcut(i: Intent?) {
        if (i?.action == ACTION_VOICE && prefs.consentGiven) {
            i.action = null
            scroll.postDelayed({ startVoice() }, 400)
        }
    }

    private fun startVoice() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!voice.canListen()) { say("Voice isn't available on this phone. Type instead."); return }
        val live = text("🎤 Listening…")
        log.addView(live)
        voice.listen(
            onPartial = { live.text = "🎤 $it" },
            onResult = { heard ->
                log.removeView(live)
                if (heard.isNullOrBlank()) { say("Didn't catch that. Tap 🎤 to try again."); return@listen }
                voiceMode = true
                progressSpoken = false
                sendText(heard, spoken = true)
            },
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    /** Reads out the top options and listens for the pick ("Ola auto", "the first one", "haan"). */
    private fun speakOptionsAndListen(out: JSONObject, pick: (String, Boolean) -> Unit) {
        val opts = out.getJSONArray("options")
        val best = out.getString("recommended_option_id")
        val spoken = (0 until minOf(3, opts.length())).joinToString(". ") { i ->
            val o = opts.getJSONObject(i)
            val eta = if (o.isNull("eta_minutes")) "" else ", ${o.getInt("eta_minutes")} minutes away"
            "${spokenName(o)}, ${Voice.rupees(o.getDouble("fare_min"))}$eta"
        }
        val choices = (0 until opts.length()).map { i ->
            val o = opts.getJSONObject(i)
            SpokenOption(o.getString("option_id"), o.getString("app"),
                o.optString("product_name").takeIf { it.isNotEmpty() && it != "null" } ?: o.getString("ride_type"),
                o.getString("ride_type"))
        }
        val names = choices.associate { it.id to spokenName(opts.getJSONObject(choices.indexOf(it))) }
        fun listenForPick() {
            val live = text("🎤 Which one?")
            log.addView(live)
            voice.listen(onPartial = { live.text = "🎤 $it" }, onResult = { heard ->
                log.removeView(live)
                when (val p = VoicePick.match(heard.orEmpty(), choices, best)) {
                    is Pick.Choose -> {
                        say("You (voice): $heard")
                        voice.speak("Opening ${names[p.id]}. Tap Book there.")
                        pick(p.id, true)
                    }
                    Pick.Decline -> { say("You (voice): $heard"); pick(best, false) }
                    Pick.Unclear -> if (++pickTries < 3) {
                        voice.speak("Sorry, which ride? Say something like Ola auto, or tap it.") { listenForPick() }
                    } else say("Tap the ride you want.")
                }
            })
        }
        pickTries = 0
        voice.speak("${out.optString("text")} Top options: $spoken. Which one?") { listenForPick() }
    }

    private fun spokenName(o: JSONObject): String {
        val app = o.getString("app").replaceFirstChar { it.uppercase() }
        val product = o.optString("product_name").takeIf { it.isNotEmpty() && it != "null" } ?: o.getString("ride_type")
        return if (product.startsWith(app, ignoreCase = true)) product else "$app $product"
    }

    companion object {
        const val ACTION_VOICE = "app.hail.VOICE"
        private const val REQ_MIC = 7
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.consentGiven) {
            startActivity(Intent(this, ConsentActivity::class.java))
            return
        }
        if (!prefs.appsSetupDone) {
            startActivity(Intent(this, AppsActivity::class.java))
            return
        }
        banner.text = when {
            prefs.simulateFares -> "TEST MODE: simulated fares"
            AppsActivity.connected(this, prefs).isEmpty() -> "No ride apps connected. Tap Apps."
            !isAccessibilityEnabled() -> "Accessibility is off. Tap here to set it up."
            else -> ""
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        voiceMode = false
        sendText(t, spoken = false)
    }

    private fun sendText(t: String, spoken: Boolean) {
        say(if (spoken) "You (voice): $t" else "You: $t")
        busy = true
        val apps = if (prefs.simulateFares) RideApps.packages.keys.toList()
        else AppsActivity.connected(this, prefs)
        runner.sendText(t, apps, this, spoken)
    }

    private fun say(s: String) {
        log.addView(text(s))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onProgress(text: String) {
        say("… $text")
        if (voiceMode && !progressSpoken) {
            progressSpoken = true
            voice.speak("Checking your ride apps.")
        }
    }

    override fun onFailure(text: String) {
        busy = false
        say(text)
        if (voiceMode) voice.speak(text)
    }

    override fun onResponse(out: JSONObject) {
        busy = false
        when (out.optString("type")) {
            "assistant_message" -> {
                say("Hail: ${out.optString("text")}")
                if (voiceMode) voice.speak(out.optString("text"))
            }
            "recommendation" -> showRecommendation(out)
            "error" -> {
                say("Hail: ${out.optString("message")} (${out.optString("code")})")
                if (voiceMode) voice.speak(out.optString("message"))
            }
            else -> say("Hail: (unexpected response)")
        }
    }

    /** Every option as a tappable row, best first; the recommended one is starred. */
    private fun showRecommendation(out: JSONObject) {
        val best = out.getString("recommended_option_id")
        val token = out.getJSONObject("confirmation").getString("token")
        val card = column().apply { setBackgroundColor(0x1A2196F3) }
        card.addView(text("Hail: ${out.optString("text")}"))
        card.addView(text("Tap a ride to open it. You book it in that app.", 13f))

        val opts = out.getJSONArray("options")
        val buttons = mutableListOf<android.widget.Button>()
        for (i in 0 until opts.length()) {
            val o = opts.getJSONObject(i)
            val id = o.getString("option_id")
            val star = if (id == best) "★ " else ""
            val saves = if (o.isNull("saves")) "" else "  ·  save ₹${o.getInt("saves")}"
            buttons += button("$star${fmt(o)}$saves") {
                buttons.forEach { it.isEnabled = false }
                voice.stopListening(); voice.stopSpeaking()
                busy = true
                runner.confirm(id, token, true, this)
            }.apply { isAllCaps = false; textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START }
        }
        buttons += button("None of these") {
            buttons.forEach { it.isEnabled = false }
            voice.stopListening(); voice.stopSpeaking()
            busy = true
            runner.confirm(best, token, false, this)
        }
        buttons.forEach { card.addView(it) }
        log.addView(card)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        if (voiceMode) speakOptionsAndListen(out) { id, accept ->
            if (buttons.first().isEnabled) {
                buttons.forEach { it.isEnabled = false }
                busy = true
                runner.confirm(id, token, accept, this, via = "voice")
            }
        }
    }

    private fun fmt(o: JSONObject): String {
        val lo = o.getDouble("fare_min")
        val hi = o.getDouble("fare_max")
        fun rs(v: Double) = if (v % 1.0 == 0.0) "₹${v.toInt()}" else "₹" + String.format(java.util.Locale.US, "%.2f", v)
        val fare = if (lo == hi) rs(lo) else "${rs(lo)}–${rs(hi)}"
        val eta = if (o.isNull("eta_minutes")) "" else " · ${o.getInt("eta_minutes")} min"
        val surge = if (o.optBoolean("surge_flag")) " · surge" else ""
        val app = o.getString("app").replaceFirstChar { it.uppercase() }
        val product = o.optString("product_name").takeIf { it.isNotEmpty() && it != "null" } ?: o.getString("ride_type")
        return "$app $product · $fare$eta$surge"
    }
}
