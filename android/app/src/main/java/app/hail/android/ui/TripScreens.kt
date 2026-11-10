package app.hail.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hail.android.RideCategory

/** Trip screens shown over Home while a request runs. */
@Composable
fun TripScreen(
    phase: Phase,
    isFavorite: (String) -> Boolean,
    onPick: (RideOption) -> Unit,
    onNone: () -> Unit,
    onCategory: (RideCategory) -> Unit,
    onReply: (String) -> Unit,
    onMic: () -> Unit,
    onFavorite: (org.json.JSONObject) -> Unit,
    onClose: () -> Unit,
) {
    when (phase) {
        is Phase.Listening -> ListeningScreen(phase.partial, onClose)
        is Phase.Checking -> CheckingScreen(phase, onClose)
        is Phase.Asking -> AskingScreen(phase, onCategory, onMic, onClose)
        is Phase.Reply -> ReplyScreen(phase.text, onReply, onMic, onClose)
        is Phase.Results -> ResultsScreen(phase, isFavorite, onPick, onNone, onFavorite, onClose)
        is Phase.Opening -> OpeningScreen(phase.app)
        is Phase.Done -> DoneScreen(phase, onClose)
        is Phase.Failed -> FailedScreen(phase.text, onClose)
        Phase.Idle -> Unit
    }
}

@Composable
private fun TopBar(title: String, onClose: () -> Unit) {
    Row2(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Muted(title, size = 15)
        Text("✕", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp), color = HailColors.Muted, fontSize = 18.sp)
    }
}

@Composable
fun ListeningScreen(partial: String, onClose: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Column(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF3A2F8F), HailColors.Bg), radius = 900f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar("Listening…", onClose)
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(180.dp).scale(s).clip(CircleShape).background(HailColors.gradient)
                .border(22.dp, HailColors.Violet.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(MicIcon, null, Modifier.size(56.dp), tint = Color.White) }
        Spacer(Modifier.height(64.dp))
        Text(
            if (partial.isBlank()) "Say where you want to go…" else "“$partial”",
            Modifier.padding(horizontal = 28.dp), fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Muted("Try “Koramangala jaana hai, sasta wala auto”", align = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Muted("Speech is turned into text on your phone. No audio leaves it.", Modifier.padding(28.dp), 13, TextAlign.Center)
    }
}

@Composable
fun CheckingScreen(p: Phase.Checking, onStop: () -> Unit) {
    val total = p.apps.size.coerceAtLeast(1)
    val done = p.apps.count { it.state == CheckState.DONE } + 0.5f * p.apps.count { it.state == CheckState.READING }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(12.dp))
        Muted("To ${p.to}" + (RideCategory.of(p.category)?.let { " · ${it.label}" } ?: ""), size = 15)
        Text("Checking your apps", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        Card {
            p.apps.forEachIndexed { i, a ->
                if (i > 0) Spacer(Modifier.height(16.dp))
                Row2 {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppBadge(a.app)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(a.app.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            Muted(when (a.state) {
                                CheckState.WAITING -> "Waiting"
                                CheckState.READING -> "Reading prices…"
                                CheckState.DONE -> "Prices read"
                            }, size = 13)
                        }
                    }
                    when (a.state) {
                        CheckState.DONE -> Tick()
                        CheckState.READING -> CircularProgressIndicator(Modifier.size(22.dp), color = HailColors.Violet, strokeWidth = 3.dp)
                        CheckState.WAITING -> Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, HailColors.SurfaceHigh, CircleShape))
                    }
                }
            }
            if (p.apps.isEmpty()) Muted("Getting ready…")
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(HailColors.Surface)) {
            Box(Modifier.fillMaxWidth(((done + 0.15f) / total).coerceIn(0.08f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(HailColors.gradient))
        }
        Spacer(Modifier.height(20.dp))
        Card {
            Row(verticalAlignment = Alignment.Top) {
                Text("🔒", fontSize = 18.sp)
                Muted("Hail reads the apps you're already logged into, right here on your phone. Only the fares leave it. Never your passwords.",
                    Modifier.padding(start = 10.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Stop", Modifier.padding(bottom = 24.dp), onClick = onStop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskingScreen(p: Phase.Asking, onCategory: (RideCategory) -> Unit, onMic: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("One quick question", onClose)
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(p.question, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                p.choices.forEach { c ->
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(HailColors.Surface).clickable { onCategory(c) }
                            .padding(horizontal = 22.dp, vertical = 16.dp),
                    ) { Text(c.label, style = MaterialTheme.typography.titleMedium) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(onClick = onMic)
                Muted("or just say it", Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
fun ReplyScreen(text: String, onReply: (String) -> Unit, onMic: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Hail", onClose)
        Column(Modifier.padding(horizontal = 22.dp)) {
            Card { Text(text, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(16.dp))
            AskBar("Type your answer…", onReply, onMic)
        }
    }
}

@Composable
fun ResultsScreen(
    p: Phase.Results,
    isFavorite: (String) -> Boolean,
    onPick: (RideOption) -> Unit,
    onNone: () -> Unit,
    onFavorite: (org.json.JSONObject) -> Unit,
    onClose: () -> Unit,
) {
    val rec = p.rec
    val best = rec.best
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Row2 {
            Muted("${RideCategory.of(rec.category)?.label ?: "Rides"} · ${rec.options.size} option${if (rec.options.size == 1) "" else "s"}", size = 15)
            val time = clockTime(rec.checkedAt)
            if (rec.live) Pill("LIVE" + (time?.let { " · $it" } ?: ""), HailColors.Mint, HailColors.MintBg)
            else Pill("TEST PRICES" + (time?.let { " · $it" } ?: ""), HailColors.Amber, HailColors.AmberBg)
        }
        Text("Best ride for you", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 6.dp))
        if (rec.text.isNotBlank()) Muted(rec.text, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        Card(gradient = true, padding = 18.dp) {
            Row2 {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    AppBadge(best.app)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(best.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(listOfNotNull(best.eta?.let { "$it min away" }, if (best.surge) "surge" else null).joinToString(" · "),
                            fontFamily = Manrope, fontSize = 13.sp, color = HailColors.Text.copy(alpha = 0.9f))
                    }
                }
                Text(best.fare, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(HailColors.Text)
                    .clickable { onPick(best) },
                contentAlignment = Alignment.Center,
            ) { Text("Open ${best.appName} · trip filled in", color = Color(0xFF2B2F8F), style = MaterialTheme.typography.labelLarge) }
            best.saves?.takeIf { it > 0 }?.let {
                Text("You save ₹$it vs the priciest similar ride", Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center, fontFamily = Manrope, fontSize = 13.sp, color = HailColors.Text.copy(alpha = 0.9f))
            }
        }
        if (rec.others.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("All options")
            rec.others.forEach { o ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HailColors.Surface)
                        .clickable { onPick(o) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppBadge(o.app)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(o.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Muted(listOfNotNull(o.eta?.let { "$it min" }, if (o.surge) "surge" else null,
                            o.saves?.takeIf { it > 0 }?.let { "save ₹$it" }).joinToString(" · "), size = 13)
                    }
                    Text(o.fare, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        rec.drop?.takeIf { it.optString("label") !in setOf("", "Current location") }?.let { drop ->
            val label = drop.optString("label")
            SecondaryButton(if (isFavorite(label)) "♥ Saved: $label" else "♡ Save $label") { onFavorite(drop) }
            Spacer(Modifier.height(10.dp))
        }
        Text("None of these", Modifier.fillMaxWidth().clickable(onClick = onNone).padding(12.dp),
            color = HailColors.Muted, textAlign = TextAlign.Center, fontFamily = Manrope, fontWeight = FontWeight.SemiBold)
        Muted("Hail opens the app with your trip. You tap Book there; Hail never books or pays for you.",
            Modifier.padding(bottom = 24.dp), 12, TextAlign.Center)
    }
}

@Composable
fun OpeningScreen(app: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = HailColors.Violet)
        Spacer(Modifier.height(24.dp))
        Text("Opening $app with your trip…", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Muted("Don't touch the phone for a moment.", Modifier.padding(top = 8.dp))
    }
}

@Composable
fun DoneScreen(p: Phase.Done, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.size(96.dp).clip(CircleShape).background(HailColors.MintBg), contentAlignment = Alignment.Center) { Tick(56.dp) }
        Spacer(Modifier.height(24.dp))
        Text("${p.app} is open with your trip", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Muted((if (p.product.isNotBlank()) "Pick ${p.product} and tap Book in ${p.app}." else "Tap Book in ${p.app}.") +
            "\nHail never books or pays for you.", Modifier.padding(top = 10.dp), 16, TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        if (p.saved != null && p.saved > 0) {
            Card(Modifier.fillMaxWidth(), padding = 22.dp) {
                SectionLabel("This ride")
                Text("You saved ₹${p.saved}", color = HailColors.Mint, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
                Muted(if (p.live) "vs the priciest similar ride" else "test prices: not counted in your savings")
            }
        } else {
            Card(Modifier.fillMaxWidth()) { Muted("This was already the best price on every app.") }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Done", Modifier.padding(bottom = 24.dp), onClick = onDone)
    }
}

@Composable
fun FailedScreen(text: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Hmm.", style = MaterialTheme.typography.headlineLarge)
        Muted(text, Modifier.padding(top = 10.dp), 16, TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Back", onClick = onClose)
    }
}
