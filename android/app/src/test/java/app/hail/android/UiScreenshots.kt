package app.hail.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the app's real screens (the same Activity code as the APK) to PNG, with a real
 * conversation recorded from the backend (tools/make_ui_fixtures.py). Runs only on request:
 *   ./gradlew screenshots -PhailScreens=1     -> app/build/ui-screens/
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshots {
    private val out = File("build/ui-screens").apply { mkdirs() }
    private val convo = JSONObject(javaClass.getResource("/ui/conversation.json")!!.readText())

    @Before fun onlyWhenAsked() = Assume.assumeTrue(System.getProperty("hail.screens") == "1")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun save(view: View, name: String) {
        idle()
        val w = view.width.takeIf { it > 0 } ?: 1179
        val h = view.height.takeIf { it > 0 } ?: 2556
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The whole scrollable chat, top to bottom, as one tall image. */
    private fun saveFull(scroll: ScrollView, name: String) {
        idle()
        val content = scroll.getChildAt(0)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(scroll.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        content.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun prefs(): Prefs = Prefs(RuntimeEnvironment.getApplication()).apply {
        consentGiven = true
        appsSetupDone = true
    }

    private fun install(pkg: String) {
        val ctx = RuntimeEnvironment.getApplication()
        val pm = shadowOf(ctx.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        pm.addResolveInfoForIntent(launch, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main" }
        })
        pm.addActivityIfNotPresent(ComponentName(pkg, "$pkg.Main"))
    }

    private fun say(a: MainActivity, s: String) {
        MainActivity::class.java.getDeclaredMethod("say", String::class.java).apply { isAccessible = true }.invoke(a, s)
    }

    private fun scrollOf(a: Activity): ScrollView {
        fun find(v: View): ScrollView? = when (v) {
            is ScrollView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!!
    }

    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun s1_consent() {
        val a = Robolectric.buildActivity(ConsentActivity::class.java).setup().get()
        save(a.window.decorView, "1_consent")
        saveFull(scrollOf(a), "1b_consent_full")
    }

    @Test fun s2_connected_apps() {
        install("com.ubercab"); install("com.olacabs.customer") // Rapido "not installed"
        val a = Robolectric.buildActivity(AppsActivity::class.java).setup().get()
        save(a.window.decorView, "2_connected_apps")
    }

    @Test fun s3_to_s6_chat() {
        prefs()
        install("com.ubercab"); install("com.olacabs.customer"); install("com.rapido.passenger")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        save(a.window.decorView, "3_chat_start")

        say(a, "🎤 ${convo.getString("said")}")
        save(a.window.decorView, "4_listening")

        val log = scrollOf(a).getChildAt(0) as ViewGroup
        log.removeViewAt(log.childCount - 1)
        say(a, "You (voice): ${convo.getString("said")}")
        a.onResponse(convo.getJSONObject("ask"))
        save(a.window.decorView, "4b_which_ride")

        buttons(scrollOf(a)).forEach { it.isEnabled = false } // the tap on "Auto"
        say(a, "You: ${RideCategory.of(convo.getString("category"))!!.label}")
        val apps = convo.getJSONArray("apps_checked")
        for (i in 0 until apps.length()) a.onProgress("Checking ${apps.getString(i).replaceFirstChar { it.uppercase() }}…")
        a.onResponse(convo.getJSONObject("recommendation"))
        save(a.window.decorView, "5_options")
        buttons(scrollOf(a)).firstOrNull { it.text.startsWith("Show ") }?.performClick()
        saveFull(scrollOf(a), "5b_options_expanded")

        buttons(scrollOf(a)).forEach { it.isEnabled = false } // what a tap does
        a.onResponse(convo.getJSONObject("done"))
        save(a.window.decorView, "6_opened")
    }

    @Test fun s7_debug() {
        prefs()
        val a = Robolectric.buildActivity(DebugActivity::class.java).setup().get()
        save(a.window.decorView, "7_debug")
    }
}
