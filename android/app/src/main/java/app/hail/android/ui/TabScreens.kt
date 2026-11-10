package app.hail.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RidesScreen(trips: List<Trip>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("Rides", style = MaterialTheme.typography.headlineLarge)
        Muted("Rides Hail opened for you. Kept on this phone only.", Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(18.dp))
        if (trips.isEmpty()) Card { Muted("No rides yet. Ask for one from Home.") }
        trips.forEach { t ->
            Card(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBadge(t.app)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("To ${t.to}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Muted("${t.app.replaceFirstChar { it.uppercase() }} ${t.product} · ${t.whenText}", size = 13)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(t.fare, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        when {
                            !t.live -> Text("test", color = HailColors.Amber, fontFamily = Manrope, fontSize = 12.sp)
                            (t.saved ?: 0) > 0 -> Text("saved ₹${t.saved}", color = HailColors.Mint, fontFamily = Manrope, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SavingsScreen(trips: List<Trip>, thisMonth: Int) {
    val live = trips.filter { it.live }
    val allTime = live.sumOf { it.saved ?: 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("Savings", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(18.dp))
        Card(gradient = true, padding = 22.dp) {
            Text("THIS MONTH", style = MaterialTheme.typography.labelSmall, color = HailColors.Text.copy(alpha = 0.85f))
            Text(rupees(thisMonth.toDouble()), fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Card(Modifier.weight(1f)) {
                SectionLabel("All time")
                Text(rupees(allTime.toDouble()), fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            Spacer(Modifier.padding(6.dp))
            Card(Modifier.weight(1f)) {
                SectionLabel("Rides compared")
                Text("${live.size}", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Muted("A saving is the gap between the ride you picked and the priciest similar ride on another app, " +
            "with live prices. Test prices never count.", size = 13)
        Spacer(Modifier.height(18.dp))
        live.filter { (it.saved ?: 0) > 0 }.take(20).forEach { t ->
            Row2(Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("To ${t.to}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Muted(t.whenText, size = 12)
                }
                Text("+₹${t.saved}", color = HailColors.Mint, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
        }
    }
}

data class AppRow(val app: String, val installed: Boolean, val enabled: Boolean)

@Composable
fun ProfileScreen(
    apps: List<AppRow>,
    accessibilityOn: Boolean,
    version: String,
    showDebug: Boolean,
    onToggleApp: (String, Boolean) -> Unit,
    onOpenApp: (String) -> Unit,
    onInstallApp: (String) -> Unit,
    onAccessibility: () -> Unit,
    onDeleteData: () -> Unit,
    onDebug: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("Your apps", style = MaterialTheme.typography.headlineLarge)
        Muted("Hail checks the ride apps you're already logged into. You stay in control.", Modifier.padding(top = 6.dp), 15)
        Spacer(Modifier.height(18.dp))
        AppsCard(apps, onToggleApp, onOpenApp, onInstallApp)

        Spacer(Modifier.height(18.dp))
        Card(onClick = onAccessibility) {
            Row2 {
                Column(Modifier.weight(1f)) {
                    Text("Reading prices", style = MaterialTheme.typography.titleMedium)
                    Muted(if (accessibilityOn) "On. Hail can check your apps." else "Off. Tap to turn on Hail in Accessibility.", size = 13)
                }
                if (accessibilityOn) Pill("ON", HailColors.Mint, HailColors.MintBg) else Pill("TURN ON", HailColors.Amber, HailColors.AmberBg)
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Your privacy")
        Spacer(Modifier.height(10.dp))
        Card {
            listOf(
                "No passwords." to "We never see or store your ride-app logins.",
                "Only fares leave your phone." to "No screenshots, no screen text.",
                "Location only when you ask." to "Never in the background.",
                "Stop anytime" to "from the notification.",
            ).forEachIndexed { i, (b, r) ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Text(buildString { append(b); append(" "); append(r) }, fontFamily = Manrope, fontSize = 14.sp, color = HailColors.Soft, lineHeight = 20.sp)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Delete my data", Modifier.fillMaxWidth().clickable(onClick = onDeleteData).padding(vertical = 12.dp),
            color = HailColors.Danger, fontFamily = Manrope, fontWeight = FontWeight.Bold)
        if (showDebug) Text("Debug tools", Modifier.fillMaxWidth().clickable(onClick = onDebug).padding(vertical = 12.dp),
            color = HailColors.Muted, fontFamily = Manrope, fontWeight = FontWeight.SemiBold)
        Muted("Hail $version", Modifier.fillMaxWidth().padding(vertical = 16.dp), 12, TextAlign.Center)
    }
}

@Composable
fun AppsCard(apps: List<AppRow>, onToggle: (String, Boolean) -> Unit, onOpen: (String) -> Unit, onInstall: (String) -> Unit) {
    Card {
        apps.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            val name = a.app.replaceFirstChar { it.uppercase() }
            Row2 {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    AppBadge(a.app)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        when {
                            !a.installed -> Text("Not installed · tap to get it", Modifier.clickable { onInstall(a.app) },
                                color = HailColors.Amber, fontFamily = Manrope, fontSize = 13.sp)
                            a.enabled -> Text("Ready · tap to open and log in", Modifier.clickable { onOpen(a.app) },
                                color = HailColors.Mint, fontFamily = Manrope, fontSize = 13.sp)
                            else -> Muted("Off", size = 13)
                        }
                    }
                }
                if (a.installed) Toggle(a.enabled) { onToggle(a.app, it) }
            }
        }
    }
}
