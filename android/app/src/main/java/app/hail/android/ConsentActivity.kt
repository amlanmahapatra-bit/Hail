package app.hail.android

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView

/** Shown before the accessibility service is enabled. States what is read, when, and what is kept. */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val col = column()
        col.addView(text("How Hail works", 22f))
        col.addView(text(getString(R.string.consent_text)))
        col.addView(button("I agree, set up accessibility") {
            prefs.consentGiven = true
            openAccessibilitySettings()
            finish()
        })
        col.addView(text(
            "If Android says the setting is restricted: open App info → ⋮ (top right) → " +
                "Allow restricted settings, then come back and try again.", 14f
        ))
        col.addView(button("Open App info") { openAppInfo() })
        col.addView(button("Not now") { finish() })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
