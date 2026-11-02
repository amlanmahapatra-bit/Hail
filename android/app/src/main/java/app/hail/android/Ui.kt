package app.hail.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers for building deliberately bare, code-only layouts. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.text(s: CharSequence, size: Float = 16f): TextView = TextView(this).apply {
    text = s
    textSize = size
    setPadding(dp(4), dp(6), dp(4), dp(6))
    setTextIsSelectable(true)
}

fun Context.button(label: CharSequence, onClick: (View) -> Unit): Button =
    Button(this).apply { text = label; setOnClickListener(onClick) }

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(12), dp(12), dp(12), dp(12))
}

fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun Context.isAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any {
        it.equals("$packageName/${HailAccessibilityService::class.java.name}", true) ||
            it.equals("$packageName/.HailAccessibilityService", true)
    }
}

fun Context.openAccessibilitySettings() =
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

/** Sideloaded apps on Android 13+ need "Allow restricted settings" from App info first. */
fun Context.openAppInfo() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
)
