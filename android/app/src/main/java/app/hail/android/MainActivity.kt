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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        runner = AgentRunner(applicationContext, prefs)

        val root = column()
        val top = row()
        top.addView(button("New") { runner.newSession(); log.removeAllViews(); say("New request.") })
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
            hint = "e.g. Powai to Andheri station, cheapest"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(button("Send") { send() })
        root.addView(bottom)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        say("Where to? Tell me pickup, drop, and cheapest / fastest.")
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.consentGiven) {
            startActivity(Intent(this, ConsentActivity::class.java))
            return
        }
        banner.text = when {
            prefs.simulateFares -> "TEST MODE: simulated fares"
            !isAccessibilityEnabled() -> "Accessibility is off. Tap here to set it up."
            else -> ""
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        say("You: $t")
        busy = true
        runner.sendText(t, this)
    }

    private fun say(s: String) {
        log.addView(text(s))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onProgress(text: String) = say("… $text")

    override fun onFailure(text: String) {
        busy = false
        say(text)
    }

    override fun onResponse(out: JSONObject) {
        busy = false
        when (out.optString("type")) {
            "assistant_message" -> say("Hail: ${out.optString("text")}")
            "recommendation" -> showRecommendation(out)
            "error" -> say("Hail: ${out.optString("message")} (${out.optString("code")})")
            else -> say("Hail: (unexpected response)")
        }
    }

    private fun showRecommendation(out: JSONObject) {
        val rec = out.getJSONObject("recommended")
        val card = column().apply { setBackgroundColor(0x1A2196F3) }
        card.addView(text("Hail: ${out.optString("text")}"))
        card.addView(text("→ ${fmt(rec)}", 18f))
        out.optJSONObject("savings_if_booked")?.let {
            card.addView(text("Saves ₹${it.getInt("amount")} vs ${fmt(it.getJSONObject("compared_against"))}", 14f))
        }
        val opts = out.getJSONArray("options")
        card.addView(text((0 until opts.length()).joinToString("\n") { "  " + fmt(opts.getJSONObject(it)) }, 13f))

        val conf = out.getJSONObject("confirmation")
        val buttons = row()
        lateinit var ok: android.widget.Button
        lateinit var no: android.widget.Button
        ok = button("Confirm") {
            ok.isEnabled = false; no.isEnabled = false
            busy = true
            runner.confirm(conf.getString("option_id"), conf.getString("token"), true, this)
        }
        no = button("Cancel") {
            ok.isEnabled = false; no.isEnabled = false
            busy = true
            runner.confirm(conf.getString("option_id"), conf.getString("token"), false, this)
        }
        buttons.addView(ok); buttons.addView(no)
        card.addView(buttons)
        log.addView(card)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun fmt(o: JSONObject): String {
        val lo = o.getDouble("fare_min").toInt()
        val hi = o.getDouble("fare_max").toInt()
        val fare = if (lo == hi) "₹$lo" else "₹$lo–$hi"
        val eta = if (o.isNull("eta_minutes")) "" else " · ${o.getInt("eta_minutes")} min"
        val surge = if (o.optBoolean("surge_flag")) " · surge" else ""
        return "${o.getString("app").replaceFirstChar { it.uppercase() }} ${o.getString("ride_type")} · $fare$eta$surge"
    }
}
