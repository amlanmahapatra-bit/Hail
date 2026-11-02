package app.hail.android

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast

class DebugActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: android.widget.TextView
    private lateinit var seen: android.widget.TextView

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
            toast("Saved")
        })

        status = text("", 14f)
        col.addView(status)
        col.addView(button("Accessibility settings") { openAccessibilitySettings() })
        col.addView(button("App info (allow restricted settings)") { openAppInfo() })

        col.addView(text("Screen dumper", 18f))
        col.addView(text(
            "1) Tap Start dump mode. 2) Open Rapido and go to the screen to capture " +
                "(e.g. the fare list). 3) Pull down notifications and tap Dump screen. " +
                "The file goes to Downloads/Hail; share it from the Files app. Dumps " +
                "contain whatever is on screen, so check them before sharing and delete " +
                "them afterwards.", 14f
        ))
        col.addView(button("Start dump mode") { AgentService.startDumpMode(this) })
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
