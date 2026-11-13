package app.hail.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hail.android.ui.AppRow
import app.hail.android.ui.ConnectAppsScreen
import app.hail.android.ui.HailApp
import app.hail.android.ui.HailController
import app.hail.android.ui.HomeScreen
import app.hail.android.ui.HomeState
import app.hail.android.ui.InviteScreen
import app.hail.android.ui.PrivacyScreen
import app.hail.android.ui.ProfileScreen
import app.hail.android.ui.RidesScreen
import app.hail.android.ui.SavingsScreen
import app.hail.android.ui.Step
import app.hail.android.ui.Tab
import app.hail.android.ui.TripLog
import app.hail.android.ui.TripScreen
import app.hail.android.ui.WelcomeScreen
import java.util.concurrent.Executors

/**
 * The Hail app: onboarding (welcome, invite, privacy, apps), then Home / Rides / Savings / Profile,
 * with the trip screens on top while a request runs. The agent, the fare reader and the
 * confirmation gate are unchanged underneath (AgentRunner).
 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var c: HailController
    private lateinit var voice: Voice
    private val io = Executors.newSingleThreadExecutor()

    /** Bumped on resume so settings read from Android (accessibility, installed apps) refresh. */
    private var tick by mutableIntStateOf(0)
    private var tab by mutableStateOf(Tab.HOME)
    private var inviteError by mutableStateOf<String?>(null)
    private var inviteWorking by mutableStateOf(false)
    private var inviteRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        voice = Voice(this)
        c = HailController(this, prefs, AgentRunner(applicationContext, prefs), voice, TripLog(this))
        Analytics.screen(this, "open")

        setContent {
            @Suppress("UNUSED_EXPRESSION") tick // re-read Android state after onResume
            val trips = run { c.tripsVersion; c.trips.all() }
            val step = step()
            HailApp(
                step = step,
                tab = tab,
                onTab = { tab = it; Analytics.screen(this, it.name.lowercase()) },
                phase = c.phase,
                onClosePhase = { c.close() },
                welcome = { WelcomeScreen { prefs.welcomeSeen = true; tick++ } },
                invite = {
                    InviteScreen(
                        error = inviteError, working = inviteWorking, requested = inviteRequested,
                        allowSkip = BuildConfig.DEBUG,
                        onJoin = { code -> join(code) },
                        onRequest = { contact, city -> requestInvite(contact, city) },
                        onSkip = { join(null) },
                        onServer = if (BuildConfig.DEBUG) ({ startActivity(Intent(this, DebugActivity::class.java)) }) else null,
                    )
                },
                privacy = {
                    PrivacyScreen(
                        onAgree = { prefs.consentGiven = true; openAccessibilitySettings(); tick++ },
                        onAppInfo = { openAppInfo() },
                    )
                },
                apps = {
                    ConnectAppsScreen(appRows(), ::toggleApp, ::openRideApp, ::installRideApp) {
                        prefs.appsSetupDone = true; tick++
                    }
                },
                home = {
                    HomeScreen(
                        HomeState(
                            category = c.category,
                            favorites = c.favorites.toList(),
                            savedThisMonth = c.trips.savedThisMonth(),
                            ridesThisMonth = trips.count { it.live },
                            setupWarning = setupWarning(),
                            testMode = prefs.simulateFares,
                        ),
                        onAsk = { text -> withRidePermissions { c.ask(text) } },
                        onMic = { mic() },
                        onCategory = { c.chooseCategory(it) },
                        onFavorite = { fav -> withRidePermissions { c.goTo(fav) } },
                        onRemoveFavorite = {
                            c.removeFavorite(it)
                            Toast.makeText(this, "Removed ${it.optString("label")}", Toast.LENGTH_SHORT).show()
                        },
                        onFixSetup = { fixSetup() },
                    )
                },
                rides = { RidesScreen(trips) },
                savings = { SavingsScreen(trips, c.trips.savedThisMonth()) },
                profile = {
                    ProfileScreen(
                        apps = appRows(), accessibilityOn = isAccessibilityEnabled(), version = BuildConfig.VERSION_NAME,
                        showDebug = BuildConfig.DEBUG,
                        onToggleApp = ::toggleApp, onOpenApp = ::openRideApp, onInstallApp = ::installRideApp,
                        onAccessibility = { openAccessibilitySettings() },
                        onDeleteData = { deleteMyData() },
                        onDebug = { startActivity(Intent(this, DebugActivity::class.java)) },
                        areaDataOn = prefs.areaDataOptIn,
                        onAreaData = { setAreaData(it) },
                    )
                },
                trip = {
                    TripScreen(
                        phase = c.phase,
                        isFavorite = { c.isFavorite(it) },
                        onPick = { c.pick(it) },
                        onNone = { c.noneOfThese() },
                        onCategory = { c.answerCategory(it) },
                        onReply = { c.ask(it) },
                        onMic = { mic() },
                        onFavorite = { c.toggleFavorite(it) },
                        onClose = { c.close() },
                    )
                },
            )
        }
        handleVoiceShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceShortcut(intent)
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ onboarding

    private fun step(): Step = when {
        !prefs.welcomeSeen -> Step.WELCOME
        prefs.deviceToken == null -> Step.INVITE
        !prefs.consentGiven -> Step.PRIVACY
        !prefs.appsSetupDone -> Step.APPS
        else -> Step.MAIN
    }

    private fun join(code: String?) {
        inviteWorking = true
        inviteError = null
        io.execute {
            val err = Api(prefs).registerWithInvite(code)
            runOnUiThread {
                inviteWorking = false
                inviteError = err
                if (err == null) { Analytics.event(this, "invite_redeemed"); tick++ }
            }
        }
    }

    private fun requestInvite(contact: String, city: String) {
        inviteWorking = true
        inviteError = null
        io.execute {
            val err = Api(prefs).requestInvite(contact, city)
            runOnUiThread {
                inviteWorking = false
                inviteError = err
                inviteRequested = err == null
            }
        }
    }

    /** Run after the first-ride permission answers (or straight away when nothing is needed). */
    private var afterRidePerms: (() -> Unit)? = null

    /**
     * Location ("pick me up here") and notifications (the "Hail is working · Stop" notice) are
     * asked the first time someone requests a ride, with the request waiting for the answers.
     * Never in setup, and never again after that: the ride works either way.
     */
    private fun withRidePermissions(then: () -> Unit) {
        val wanted = ridePermissionsMissing()
        if (prefs.ridePermsAsked || wanted.isEmpty()) return then()
        prefs.ridePermsAsked = true
        afterRidePerms = then
        requestPermissions(wanted.toTypedArray(), REQ_PERMS)
    }

    private fun ridePermissionsMissing(): List<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        // Location: only for "pickup = where I am", read when you send a request.
        if (!Here.granted(this)) wanted += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        return wanted
    }

    // ------------------------------------------------------------------ apps and setup

    private fun appRows() = RideApps.packages.keys.map {
        AppRow(it, RideApps.isInstalled(this, it), it in prefs.enabledApps)
    }

    private fun toggleApp(app: String, on: Boolean) {
        prefs.enabledApps = if (on) prefs.enabledApps + app else prefs.enabledApps - app
        tick++
    }

    private fun openRideApp(app: String) {
        RideApps.packages[app]?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
    }

    private fun installRideApp(app: String) {
        val pkg = RideApps.packages[app] ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) } }
    }

    private fun setupWarning(): String? = when {
        prefs.simulateFares -> null
        RideApps.connected(this, prefs).isEmpty() -> "No ride apps connected yet."
        !isAccessibilityEnabled() -> "Turn on Hail in Accessibility so it can check prices."
        else -> null
    }

    private fun fixSetup() {
        if (RideApps.connected(this, prefs).isEmpty()) tab = Tab.PROFILE else openAccessibilitySettings()
    }

    private fun setAreaData(optIn: Boolean) {
        val before = prefs.areaDataOptIn
        prefs.areaDataOptIn = optIn
        tick++
        io.execute {
            val ok = runCatching { Api(prefs).setAreaData(optIn) }.isSuccess
            runOnUiThread {
                if (!ok) {
                    prefs.areaDataOptIn = before
                    tick++
                    Toast.makeText(this, "Couldn't reach Hail. Try again.", Toast.LENGTH_LONG).show()
                } else {
                    Analytics.event(this, "area_data", "input" to if (optIn) "on" else "off")
                }
            }
        }
    }

    private fun deleteMyData() {
        c.deleteMyData { ok ->
            if (ok) {
                Analytics.reset(this)
                prefs.wipe()
                Toast.makeText(this, "Deleted. Hail starts fresh.", Toast.LENGTH_LONG).show()
                tick++
            } else Toast.makeText(this, "Couldn't reach Hail. Try again.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ voice

    private fun mic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        c.startVoice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) c.startVoice()
        if (requestCode == REQ_PERMS) afterRidePerms?.also { afterRidePerms = null }?.invoke()
    }

    /** Long-press the Hail icon → "Speak to Hail" opens straight into listening. */
    private fun handleVoiceShortcut(i: Intent?) {
        if (i?.action == ACTION_VOICE && step() == Step.MAIN) {
            i.action = null
            window.decorView.postDelayed({ mic() }, 400)
        }
    }

    companion object {
        const val ACTION_VOICE = "app.hail.VOICE"
        private const val REQ_MIC = 7
        private const val REQ_PERMS = 1
    }
}
