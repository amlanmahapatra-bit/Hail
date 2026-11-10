package app.hail.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String) { HOME("Home"), RIDES("Rides"), SAVINGS("Savings"), PROFILE("Profile") }

enum class Step { WELCOME, INVITE, PRIVACY, APPS, MAIN }

/** The whole app. Every value and action comes from MainActivity, so screens stay testable. */
@Composable
fun HailApp(
    step: Step,
    tab: Tab,
    onTab: (Tab) -> Unit,
    phase: Phase,
    onClosePhase: () -> Unit,
    welcome: @Composable () -> Unit,
    invite: @Composable () -> Unit,
    privacy: @Composable () -> Unit,
    apps: @Composable () -> Unit,
    home: @Composable () -> Unit,
    rides: @Composable () -> Unit,
    savings: @Composable () -> Unit,
    profile: @Composable () -> Unit,
    trip: @Composable () -> Unit,
) {
    HailTheme {
        Box(Modifier.fillMaxSize().background(HailColors.Bg)) {
            when (step) {
                Step.WELCOME -> welcome()
                Step.INVITE -> Box(Modifier.statusBarsPadding().navigationBarsPadding()) { invite() }
                Step.PRIVACY -> Box(Modifier.statusBarsPadding().navigationBarsPadding()) { privacy() }
                Step.APPS -> Box(Modifier.statusBarsPadding().navigationBarsPadding()) { apps() }
                Step.MAIN -> {
                    val inTrip = phase != Phase.Idle
                    BackHandler(enabled = inTrip, onBack = onClosePhase)
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.weight(1f)) {
                            AnimatedContent(inTrip, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "trip") { trip ->
                                if (trip) trip() else when (tab) {
                                    Tab.HOME -> home()
                                    Tab.RIDES -> rides()
                                    Tab.SAVINGS -> savings()
                                    Tab.PROFILE -> profile()
                                }
                            }
                        }
                        if (!inTrip) NavBar(tab, onTab)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(HailColors.Nav).navigationBarsPadding().height(64.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            Column(
                Modifier.clip(CircleShape).clickable { onTab(t) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, color = if (t == tab) HailColors.Text else HailColors.Muted, fontFamily = Manrope,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Box(Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape)
                    .background(if (t == tab) HailColors.Violet else HailColors.Nav))
            }
        }
    }
}
