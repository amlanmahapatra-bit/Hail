package app.hail.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads ride-app screens and (later) taps. It does NOTHING unless AgentState.active is true.
 * The XML config also restricts it to the three ride-app packages.
 */
class HailAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AgentState.active || AgentState.stopRequested) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != AgentState.targetPackage) return
        // Week 1: no parsing yet. In debug builds, keep fare-like text in memory for the
        // Debug screen so we can see what the reader sees. Never persisted or sent.
        if (!BuildConfig.DEBUG) return
        val root = rootInActiveWindow ?: return
        val found = mutableListOf<String>()
        collectFareText(root, found, 0)
        for (t in found) {
            if (t !in AgentState.seenFareText) {
                AgentState.seenFareText.add(t)
                Log.d(TAG, "fare text [$pkg]: $t")
            }
        }
    }

    private fun collectFareText(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 60 || out.size > 50) return
        listOfNotNull(n.text, n.contentDescription).map { it.toString() }
            .filter { it.contains('₹') || it.contains("Rs", ignoreCase = false) }
            .forEach { out.add(it.take(120)) }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectFareText(it, out, depth + 1) }
    }

    /**
     * Debug dumper. Dumps the node tree of the ride app's window. Returns null if no ride
     * app window is on screen. Only called when the user taps "Dump screen".
     */
    fun dumpRideAppWindow(): JSONObject? {
        val root = findRideAppRoot() ?: return null
        val nodes = JSONArray()
        walk(root, 0, nodes)
        val metrics = resources.displayMetrics
        return JSONObject()
            .put("format", "hail.a11y_dump.v1")
            .put("package", root.packageName?.toString())
            .put("captured_at", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("app_version", appVersion(root.packageName?.toString()))
            .put("screen", JSONObject().put("w", metrics.widthPixels).put("h", metrics.heightPixels))
            .put("node_count", nodes.length())
            .put("nodes", nodes)
    }

    /**
     * Text + bounds of every node in [pkg]'s window. Used only by the fare reader, only while
     * a user-started check is running, and never stored or sent: the reader turns it into
     * catalog keys and numbers.
     */
    fun snapshot(pkg: String): List<ScreenNode>? {
        if (!AgentState.active) return null
        val root = rootFor(pkg) ?: return null
        val out = ArrayList<ScreenNode>(256)
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80 || out.size > 4000) return
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (!t.isNullOrBlank() || !d.isNullOrBlank()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                out += ScreenNode(t, d, r.left, r.top, r.right, r.bottom)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** Scrolls the biggest scrollable list in [pkg]'s window. False if nothing scrolled. */
    fun scrollForward(pkg: String): Boolean {
        if (!AgentState.active) return false
        val root = rootFor(pkg) ?: return false
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80) return
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    }

    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == pkg }
            ?.let { return it }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
    }

    fun dismissShade() {
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    private fun findRideAppRoot(): AccessibilityNodeInfo? {
        val ride = RideApps.packages.values.toSet()
        windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() in ride }
            ?.let { return it }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() in ride }
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: JSONArray) {
        if (depth > 80 || out.length() > 5000) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val o = JSONObject()
            .put("depth", depth)
            .put("class", n.className?.toString())
            .put("view_id", n.viewIdResourceName)
            .put("text", n.text?.toString())
            .put("content_desc", n.contentDescription?.toString())
            .put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            .put("clickable", n.isClickable)
            .put("scrollable", n.isScrollable)
            .put("editable", n.isEditable)
            .put("selected", n.isSelected)
            .put("checked", n.isChecked)
            .put("enabled", n.isEnabled)
            .put("visible", n.isVisibleToUser)
            .put("child_count", n.childCount)
        o.put("hint", n.hintText?.toString())
        out.put(o)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, out) }
    }

    private fun appVersion(pkg: String?): String? = pkg?.let {
        runCatching { packageManager.getPackageInfo(it, 0).versionName }.getOrNull()
    }

    companion object {
        private const val TAG = "HailA11y"
        @Volatile var instance: HailAccessibilityService? = null
    }
}
