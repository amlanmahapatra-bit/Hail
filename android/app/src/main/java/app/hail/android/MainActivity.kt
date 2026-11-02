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
import android.view.View
import org.json.JSONObject

/** The bare chat screen. */
class MainActivity : Activity(), AgentRunner.Listener {
    private lateinit var prefs: Prefs
    private lateinit var runner: AgentRunner
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var banner: TextView
    private lateinit var spinner: android.widget.Spinner
    private lateinit var favRow: LinearLayout
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
        root.addView(rideDropdown())
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
        root.addView(favoritesBar())
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
        private const val TOP_ROWS = 4
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
        renderFavorites()
        refreshFavorites()
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

    private fun sendText(t: String, spoken: Boolean, category: String? = null, destination: JSONObject? = null) {
        say(if (spoken) "You (voice): $t" else "You: $t")
        busy = true
        val apps = if (prefs.simulateFares) RideApps.packages.keys.toList()
        else AppsActivity.connected(this, prefs)
        // A ride type in the words ("rickshaw", "big cab") wins; else the dropdown; else Hail asks.
        val cat = category ?: RideCategory.detect(t)?.key ?: prefs.rideCategory
        if (cat != null && cat != prefs.rideCategory) rememberRideType(cat)
        runner.sendText(t, apps, this, spoken, cat, destination)
    }

    // ------------------------------------------------------------------ ride type

    /** "Ride type: Any / Auto / Bike / Cab / Cab XL". Any = Hail asks for each trip. */
    private fun rideDropdown(): View {
        val r = row().apply { setPadding(dp(4), 0, dp(4), 0); gravity = android.view.Gravity.CENTER_VERTICAL }
        r.addView(text("Ride type", 15f))
        val labels = listOf("Any (ask me)") + RideCategory.entries.map { it.label }
        spinner = android.widget.Spinner(this)
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(RideCategory.entries.indexOfFirst { it.key == prefs.rideCategory } + 1)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.rideCategory = if (pos == 0) null else RideCategory.entries[pos - 1].key
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        r.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    /** The last ride type used becomes the dropdown's value, so Hail stops asking. */
    private fun rememberRideType(key: String) {
        prefs.rideCategory = key
        spinner.setSelection(RideCategory.entries.indexOfFirst { it.key == key } + 1)
    }

    // ------------------------------------------------------------------ favourites

    /** One-tap favourite places above the text box. Long-press one to remove it. */
    private fun favoritesBar(): View {
        favRow = row().apply { setPadding(0, dp(2), 0, dp(2)) }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(favRow)
        }
    }

    private fun favorites(): List<JSONObject> = runCatching {
        val a = org.json.JSONArray(prefs.favoritesCache)
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun renderFavorites() {
        favRow.removeAllViews()
        for (f in favorites()) {
            favRow.addView(button("♥ ${f.getString("label")}") { goTo(f) }.apply {
                isAllCaps = false
                setOnLongClickListener { removeFavorite(f); true }
            })
        }
        favRow.visibility = if (favRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun refreshFavorites() = runner.favorites({ it.favorites() }) { arr ->
        if (arr != null) { prefs.favoritesCache = arr.toString(); renderFavorites() }
    }

    private fun goTo(f: JSONObject) {
        if (busy) return
        voiceMode = false
        val label = f.getString("label")
        val dest = JSONObject().put("label", label)
            .put("lat", if (f.isNull("lat")) JSONObject.NULL else f.getDouble("lat"))
            .put("lng", if (f.isNull("lng")) JSONObject.NULL else f.getDouble("lng"))
        sendText("to $label", spoken = false, destination = dest)
    }

    private fun removeFavorite(f: JSONObject) = runner.favorites({ it.removeFavorite(f.getInt("id")) }) { ok ->
        if (ok != null) {
            android.widget.Toast.makeText(this, "Removed ${f.getString("label")}", android.widget.Toast.LENGTH_SHORT).show()
            refreshFavorites()
        }
    }

    /** "♡ Save Andheri Station" on a results card; tap again to un-save. */
    private fun heartButton(drop: JSONObject): View {
        val label = drop.getString("label")
        fun saved() = favorites().firstOrNull { it.getString("label").equals(label, ignoreCase = true) }
        val b = button("") {}.apply { isAllCaps = false }
        fun paint() { b.text = if (saved() != null) "♥ Saved to favourites" else "♡ Save $label to favourites" }
        b.setOnClickListener {
            val existing = saved()
            if (existing != null) {
                runner.favorites({ it.removeFavorite(existing.getInt("id")) }) { refreshFavorites(); paint() }
                prefs.favoritesCache = org.json.JSONArray(favorites().filter { it !== existing && it.getInt("id") != existing.getInt("id") }).toString()
            } else {
                val lat = if (drop.isNull("lat")) null else drop.getDouble("lat")
                val lng = if (drop.isNull("lng")) null else drop.getDouble("lng")
                runner.favorites({ it.addFavorite(label, lat, lng) }) { fav ->
                    if (fav != null) {
                        prefs.favoritesCache = org.json.JSONArray(favorites() + fav).toString()
                        renderFavorites(); paint()
                    }
                }
            }
            paint()
        }
        paint()
        return b
    }

    /** "Which ride would you like?" with one button per type; voice answers work too. */
    private fun showAsk(out: JSONObject) {
        val q = out.optString("text")
        say("Hail: $q")
        val choices = out.getJSONObject("ask").getJSONArray("choices")
        val cats = (0 until choices.length()).mapNotNull { RideCategory.of(choices.getString(it)) }
        val bar = row().apply { setPadding(0, dp(4), 0, dp(8)) }
        val buttons = mutableListOf<android.widget.Button>()
        cats.forEach { c ->
            buttons += button(c.label) {
                buttons.forEach { it.isEnabled = false }
                voice.stopListening(); voice.stopSpeaking()
                sendText(c.label, spoken = false, category = c.key)
            }.also {
                it.isAllCaps = false
                it.textSize = 13f
                it.setPadding(0, it.paddingTop, 0, it.paddingBottom)
                bar.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        log.addView(bar)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        if (!voiceMode) return
        fun listenForType(tries: Int) {
            val live = text("🎤 Auto, bike, cab, premium or cab XL?")
            log.addView(live)
            voice.listen(onPartial = { live.text = "🎤 $it" }, onResult = { heard ->
                log.removeView(live)
                val c = heard?.let { RideCategory.detect(it) }
                when {
                    !buttons.first().isEnabled -> Unit // already tapped
                    c != null -> { buttons.forEach { it.isEnabled = false }; sendText(heard, spoken = true, category = c.key) }
                    tries < 2 -> voice.speak("Sorry, say auto, bike, cab, premium or cab XL.") { listenForType(tries + 1) }
                    else -> say("Tap the ride type you want.")
                }
            })
        }
        voice.speak(q) { listenForType(0) }
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
            "assistant_message" -> if (out.optJSONObject("ask") != null) showAsk(out) else {
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

    /**
     * The options for the chosen ride type, best first. Shows the top few with "Show more", a
     * header with the ride type, and whether prices are live and when they were read.
     */
    private fun showRecommendation(out: JSONObject) {
        val best = out.getString("recommended_option_id")
        val token = out.getJSONObject("confirmation").getString("token")
        val opts = out.getJSONArray("options")
        val card = column().apply { setBackgroundColor(0x142196F3) }

        val cat = RideCategory.of(out.optString("ride_category"))?.label ?: "Rides"
        card.addView(text("$cat · ${opts.length()} option${if (opts.length() == 1) "" else "s"}", 18f))
        card.addView(text(pricesLine(out), 13f).apply {
            setTextColor(if (out.optBoolean("live", true)) 0xFF2E7D32.toInt() else 0xFFB26A00.toInt())
        })
        card.addView(text(out.optString("text"), 15f))

        val buttons = mutableListOf<android.widget.Button>()
        val hidden = mutableListOf<View>()
        for (i in 0 until opts.length()) {
            val o = opts.getJSONObject(i)
            val id = o.getString("option_id")
            val b = button(rowText(o, id == best)) {
                buttons.forEach { it.isEnabled = false }
                voice.stopListening(); voice.stopSpeaking()
                busy = true
                runner.confirm(id, token, true, this)
            }.apply {
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
            buttons += b
            card.addView(b)
            if (i >= TOP_ROWS) { b.visibility = View.GONE; hidden += b }
        }
        if (hidden.isNotEmpty()) {
            card.addView(button("Show ${hidden.size} more") { v ->
                hidden.forEach { it.visibility = View.VISIBLE }
                v.visibility = View.GONE
            }.apply { isAllCaps = false })
        }
        val none = button("None of these") {
            buttons.forEach { it.isEnabled = false }
            voice.stopListening(); voice.stopSpeaking()
            busy = true
            runner.confirm(best, token, false, this)
        }.apply { isAllCaps = false; alpha = 0.7f }
        buttons += none
        card.addView(none)
        out.optJSONObject("drop")?.takeIf { it.optString("label") !in setOf("", "Current location") }
            ?.let { card.addView(heartButton(it)) }
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

    /** "★ Ola Auto  ₹46" on top, "1 min away · save ₹3" below. */
    private fun rowText(o: JSONObject, best: Boolean): CharSequence {
        val star = if (best) "★ " else ""
        val first = "$star${fmtName(o)}   ${fmtFare(o)}"
        val bits = mutableListOf<String>()
        if (!o.isNull("eta_minutes")) bits += "${o.getInt("eta_minutes")} min away"
        if (o.optBoolean("surge_flag")) bits += "surge"
        if (!o.isNull("saves")) bits += "save ₹${o.getInt("saves")}"
        val sb = android.text.SpannableStringBuilder(first)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, first.length, 0)
        if (bits.isNotEmpty()) {
            val start = sb.length
            sb.append("\n").append(bits.joinToString(" · "))
            sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, sb.length, 0)
        }
        return sb
    }

    private fun pricesLine(out: JSONObject): String {
        val at = out.optString("prices_checked_at").takeIf { it.isNotEmpty() && it != "null" }
        val time = at?.let {
            runCatching {
                java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.ENGLISH))
            }.getOrNull()
        }
        val live = out.optBoolean("live", true)
        return when {
            !live -> "Test prices, not live" + (time?.let { " · $it" } ?: "")
            time != null -> "Live prices · updated $time"
            else -> "Live prices from the apps"
        }
    }

    private fun fmtName(o: JSONObject): String {
        val app = o.getString("app").replaceFirstChar { it.uppercase() }
        val product = o.optString("product_name").takeIf { it.isNotEmpty() && it != "null" } ?: o.getString("ride_type")
        return if (product.startsWith(app, ignoreCase = true)) product else "$app $product"
    }

    private fun fmtFare(o: JSONObject): String {
        val lo = o.getDouble("fare_min")
        val hi = o.getDouble("fare_max")
        fun rs(v: Double) = if (v % 1.0 == 0.0) "₹${v.toInt()}" else "₹" + String.format(java.util.Locale.US, "%.2f", v)
        return if (lo == hi) rs(lo) else "${rs(lo)}–${rs(hi)}"
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
        val name = if (product.startsWith(app, ignoreCase = true)) product else "$app $product"
        return "$name · $fare$eta$surge"
    }
}
