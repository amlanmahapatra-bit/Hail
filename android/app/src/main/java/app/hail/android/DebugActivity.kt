package app.hail.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast

class DebugActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: android.widget.TextView
    private lateinit var seen: android.widget.TextView
    private lateinit var testResult: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = column()

        col.addView(text("Backend URL", 14f))
        val url = EditText(this).apply { setText(prefs.backendUrl); isSingleLine = true }
        col.addView(url)
        col.addView(button("Save URL") {
            prefs.backendUrl = url.text.toString().trim()
            RideApps.config = null
            RideApps.readers = emptyMap()
            toast("Saved")
        })

        status = text("", 14f)
        col.addView(status)
        col.addView(button("Accessibility settings") { openAccessibilitySettings() })
        col.addView(button("App info (allow restricted settings)") { openAppInfo() })

        col.addView(text("Test fare check (no server needed)", 18f))
        col.addView(text(
            "Type a destination, then tap an app. Hail opens it, finds your trip (pickup = where " +
                "you are) and reads the fares. Hail never books. Results stay on this phone.", 14f
        ))
        val dest = EditText(this).apply { hint = "e.g. Koramangala Forum Mall"; isSingleLine = true }
        col.addView(dest)
        testResult = text("", 13f)
        val apps = row()
        for (app in listOf("uber", "ola", "rapido")) {
            apps.addView(button(app.replaceFirstChar { it.uppercase() }) {
                val d = dest.text.toString().trim()
                if (d.isEmpty()) { toast("Type a destination first"); return@button }
                testResult.text = "Checking $app…"
                AgentRunner(applicationContext, prefs).testCheck(app, d) { testResult.text = it }
            })
        }
        col.addView(apps)
        col.addView(testResult)

        col.addView(text("Screen dumper", 18f))
        col.addView(text(
            "Tap Dump in 10 seconds, then open the ride app on the screen to capture " +
                "(e.g. the fare list) and wait. Or: Start dump mode, open the app, pull down " +
                "notifications and tap the Hail notification. " +
                "The file goes to Downloads/Hail; share it from the Files app. Dumps " +
                "contain whatever is on screen, so check them before sharing and delete " +
                "them afterwards.", 14f
        ))
        col.addView(button("Start dump mode") { AgentService.startDumpMode(this) })
        col.addView(button("Dump in 10 seconds (open the ride app now)") {
            AgentService.startDumpMode(this)
            android.os.Handler(mainLooper).postDelayed({
                startService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_DUMP))
            }, 10_000)
        })
        col.addView(button("Stop dump mode") { AgentService.hide(this) })

        if (BuildConfig.DEBUG) {
            col.addView(CheckBox(this).apply {
                text = "Simulate fares (test data, no ride apps opened)"
                isChecked = prefs.simulateFares
                setOnCheckedChangeListener { _, v -> prefs.simulateFares = v }
            })
            col.addView(text("Fare text seen during last check (memory only)", 18f))
            seen = text("", 13f)
            col.addView(seen)
        }

        col.addView(text("Privacy", 18f))
        col.addView(button("Delete my data (server + this phone)") {
            AgentRunner(this, prefs).deleteMyData { ok ->
                if (ok) {
                    prefs.wipe()
                    AgentState.seenFareText.clear()
                    toast("Deleted. Hail is reset.")
                    finishAffinity()
                } else toast("Could not reach the server; nothing deleted.")
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = "Accessibility service: " + if (isAccessibilityEnabled()) "ON" else "OFF"
        if (BuildConfig.DEBUG) {
            seen.text = AgentState.seenFareText.joinToString("\n").ifEmpty { "(none)" }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
