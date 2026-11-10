package app.hail.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import app.hail.android.ui.AppCheck
import app.hail.android.ui.AppRow
import app.hail.android.ui.CheckState
import app.hail.android.ui.HailApp
import app.hail.android.ui.HomeScreen
import app.hail.android.ui.HomeState
import app.hail.android.ui.InviteScreen
import app.hail.android.ui.ConnectAppsScreen
import app.hail.android.ui.Phase
import app.hail.android.ui.PrivacyScreen
import app.hail.android.ui.ProfileScreen
import app.hail.android.ui.Recommendation
import app.hail.android.ui.RidesScreen
import app.hail.android.ui.SavingsScreen
import app.hail.android.ui.Step
import app.hail.android.ui.Tab
import app.hail.android.ui.Trip
import app.hail.android.ui.TripScreen
import app.hail.android.ui.WelcomeScreen
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
 * Renders the app's screens (the same Compose code as the APK) to PNG with a real conversation
 * recorded from the backend (tools/make_ui_fixtures.py). Runs only on request:
 *   ./gradlew testDebugUnitTest -PhailScreens=1 --tests '*UiScreenshots*'  -> app/build/ui-screens/
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshots {
    private val out = File("build/ui-screens").apply { mkdirs() }
    private val convo = JSONObject(javaClass.getResource("/ui/conversation.json")!!.readText())
    private val rec = Recommendation.from(convo.getJSONObject("recommendation"))
    private val favs = listOf(
        JSONObject("""{"id":1,"label":"College","lat":null,"lng":null}"""),
        JSONObject("""{"id":2,"label":"Forum Mall","lat":12.9352,"lng":77.6245}"""),
        JSONObject("""{"id":3,"label":"MG Road","lat":12.9756,"lng":77.605}"""),
    )
    private val trips = listOf(
        Trip(1_793_000_000_000, "Koramangala", "ola", "Auto", "₹46", 3, true),
        Trip(1_792_900_000_000, "MG Road", "rapido", "Cab Non AC", "₹101", 17, true),
        Trip(1_792_800_000_000, "Forum Mall", "uber", "Auto", "₹49.40", null, true),
    )
    private val apps = listOf(AppRow("uber", true, true), AppRow("ola", true, true), AppRow("rapido", false, false))

    @Before fun onlyWhenAsked() = Assume.assumeTrue(System.getProperty("hail.screens") == "1")

    private fun shot(name: String, content: @Composable () -> Unit) {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        a.setContent(content = content)
        shadowOf(Looper.getMainLooper()).idle()
        val v: View = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width.takeIf { it > 0 } ?: 1179, v.height.takeIf { it > 0 } ?: 2556, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun main(tab: Tab = Tab.HOME, phase: Phase = Phase.Idle, body: @Composable () -> Unit) = @Composable {
        HailApp(
            step = Step.MAIN, tab = tab, onTab = {}, phase = phase, onClosePhase = {},
            welcome = {}, invite = {}, privacy = {}, apps = {},
            home = body, rides = body, savings = body, profile = body, trip = body,
        )
    }

    private fun onboarding(step: Step, body: @Composable () -> Unit) = @Composable {
        HailApp(
            step = step, tab = Tab.HOME, onTab = {}, phase = Phase.Idle, onClosePhase = {},
            welcome = body, invite = body, privacy = body, apps = body,
            home = {}, rides = {}, savings = {}, profile = {}, trip = {},
        )
    }

    private fun trip(phase: Phase) = main(phase = phase) {
        TripScreen(phase, { it == "Koramangala" }, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun onboarding() {
        shot("01_welcome", onboarding(Step.WELCOME) { WelcomeScreen {} })
        shot("02_invite", onboarding(Step.INVITE) { InviteScreen(null, false, false, false, {}, { _, _ -> }, {}) })
        shot("03_privacy", onboarding(Step.PRIVACY) { PrivacyScreen({}, {}) })
        shot("04_connect_apps", onboarding(Step.APPS) { ConnectAppsScreen(apps, { _, _ -> }, {}, {}, {}) })
    }

    @Test fun home_and_tabs() {
        val home = HomeState("auto", favs, 20, 3, null, false)
        shot("10_home", main { HomeScreen(home, {}, {}, {}, {}, {}, {}) })
        shot("11_home_setup", main { HomeScreen(HomeState(null, emptyList(), 0, 0, "Turn on Hail in Accessibility so it can check prices.", false), {}, {}, {}, {}, {}, {}) })
        shot("30_rides", main(Tab.RIDES) { RidesScreen(trips) })
        shot("31_savings", main(Tab.SAVINGS) { SavingsScreen(trips, 20) })
        shot("32_profile", main(Tab.PROFILE) { ProfileScreen(apps, true, "0.2.0", true, { _, _ -> }, {}, {}, {}, {}, {}) })
    }

    @Test fun trip_flow() {
        shot("20_listening", trip(Phase.Listening(convo.getString("said"))))
        shot("21_checking", trip(Phase.Checking("Koramangala", "auto", listOf(
            AppCheck("uber", CheckState.DONE), AppCheck("ola", CheckState.DONE), AppCheck("rapido", CheckState.READING)))))
        shot("22_which_ride", trip(Phase.Asking(convo.getJSONObject("ask").getString("text"),
            RideCategory.entries.toList())))
        shot("23_results", trip(Phase.Results(rec, "Koramangala")))
        shot("24_opening", trip(Phase.Opening("Ola")))
        shot("25_done", trip(Phase.Done("Ola", "Auto", convo.getJSONObject("done").optString("text"), 3, false)))
        shot("26_reply", trip(Phase.Reply("Where should I pick you up from?")))
    }
}
