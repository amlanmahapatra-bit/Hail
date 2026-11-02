package app.hail.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Connected apps. Hail uses the Uber, Ola and Rapido apps already on the phone, logged in with
 * the customer's own account. The customer logs in once, inside each official app, the way
 * they normally would. Hail never sees passwords or OTPs and never stores a login.
 */
class AppsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = column()
        col.addView(text("Connected apps", 22f))
        col.addView(text(
            "Hail checks fares in the ride apps on this phone, using your own accounts. " +
                "Log in once inside each app as you normally would; you stay logged in and " +
                "never have to log in again for Hail. Hail never sees your password or OTP.", 14f
        ))
        list = column().apply { setPadding(0, 0, 0, 0) }
        col.addView(list)
        col.addView(button("Done") {
            prefs.appsSetupDone = true
            finish()
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        for ((app, pkg) in RideApps.packages) {
            val name = app.replaceFirstChar { it.uppercase() }
            val installed = RideApps.isInstalled(this, app)
            val box = column().apply { setPadding(0, dp(8), 0, dp(8)) }
            box.addView(text(name, 18f))
            if (!installed) {
                box.addView(text("Not installed.", 14f))
                box.addView(button("Get $name from Play Store") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                    }
                })
            } else {
                box.addView(CheckBox(this).apply {
                    text = "Use $name with Hail"
                    isChecked = app in prefs.enabledApps
                    setOnCheckedChangeListener { _, on ->
                        prefs.enabledApps = if (on) prefs.enabledApps + app else prefs.enabledApps - app
                    }
                })
                box.addView(button("Open $name to log in") {
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                })
            }
            list.addView(box)
        }
    }

    companion object {
        /** Apps Hail may use right now: installed and switched on. */
        fun connected(activity: Activity, prefs: Prefs): List<String> =
            RideApps.packages.keys.filter { it in prefs.enabledApps && RideApps.isInstalled(activity, it) }
    }
}
