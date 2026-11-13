package app.hail.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hail.android.R

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().background(HailColors.gradient).padding(28.dp)) {
        Spacer(Modifier.weight(1f))
        Image(painterResource(R.drawable.hail_wordmark), contentDescription = "Hail", Modifier.height(72.dp))
        Spacer(Modifier.height(20.dp))
        Text("One request.\nEvery cab app.\nThe best ride, ready to book.", fontFamily = Manrope, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, lineHeight = 32.sp, color = HailColors.Text)
        Spacer(Modifier.height(18.dp))
        Text("Hail checks Uber, Ola and Rapido for you, shows the best ride, and opens that app with your trip filled in.",
            fontFamily = Manrope, fontSize = 15.sp, lineHeight = 22.sp, color = HailColors.Text.copy(alpha = 0.9f))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(HailColors.Text).clickable(onClick = onStart),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text("Get started", color = HailColors.Blue, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Invite-only beta: enter a code, or ask for one. */
@Composable
fun InviteScreen(
    error: String?,
    working: Boolean,
    requested: Boolean,
    allowSkip: Boolean,
    onJoin: (String) -> Unit,
    onRequest: (contact: String, city: String) -> Unit,
    onSkip: () -> Unit,
    onServer: (() -> Unit)? = null,
) {
    var code by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var contact by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Pill("INVITE-ONLY BETA", HailColors.Violet, HailColors.Surface)
        Spacer(Modifier.height(14.dp))
        Text(if (asking) "Request an invite" else "Got an invite code?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        if (!asking) {
            Muted("Hail is opening to a few riders at a time. Enter the code you were sent.", size = 15)
            Spacer(Modifier.height(20.dp))
            Field(code, { code = it.uppercase() }, "HAIL-XXXXXX", caps = true)
            if (error != null) Text(error, Modifier.padding(top = 8.dp), color = HailColors.Danger, fontFamily = Manrope, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (working) "Checking…" else "Join Hail", enabled = !working && code.length >= 4) { onJoin(code.trim()) }
            Spacer(Modifier.height(12.dp))
            Text("Don't have one? Request an invite", Modifier.fillMaxWidth().clickable { asking = true }.padding(12.dp),
                color = HailColors.Soft, textAlign = TextAlign.Center, fontFamily = Manrope, fontWeight = FontWeight.SemiBold)
            if (allowSkip) Text("Continue without a code (test build)", Modifier.fillMaxWidth().clickable(onClick = onSkip).padding(12.dp),
                color = HailColors.Muted, textAlign = TextAlign.Center, fontFamily = Manrope, fontSize = 13.sp)
            if (onServer != null) Text("Server settings (test build)", Modifier.fillMaxWidth().clickable(onClick = onServer).padding(12.dp),
                color = HailColors.Muted, textAlign = TextAlign.Center, fontFamily = Manrope, fontSize = 13.sp)
        } else if (requested) {
            Card { Text("Thanks! We'll send you a code on WhatsApp or email.", style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("I have a code") { asking = false }
        } else {
            Muted("Leave your WhatsApp number or email. We'll send you a code when there's a spot. It's used only to invite you.", size = 15)
            Spacer(Modifier.height(20.dp))
            Field(contact, { contact = it }, "WhatsApp number or email")
            Spacer(Modifier.height(10.dp))
            Field(city, { city = it }, "Your city (optional)")
            if (error != null) Text(error, Modifier.padding(top = 8.dp), color = HailColors.Danger, fontFamily = Manrope, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (working) "Sending…" else "Request invite", enabled = !working && contact.trim().length >= 5) {
                onRequest(contact.trim(), city.trim())
            }
            Spacer(Modifier.height(8.dp))
            Text("Back", Modifier.fillMaxWidth().clickable { asking = false }.padding(12.dp),
                color = HailColors.Muted, textAlign = TextAlign.Center, fontFamily = Manrope)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, caps: Boolean = false) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HailColors.Surface).padding(horizontal = 18.dp, vertical = 18.dp)) {
        if (value.isEmpty()) Muted(hint, size = 16)
        BasicTextField(
            value, onChange, singleLine = true, cursorBrush = SolidColor(HailColors.Violet),
            textStyle = TextStyle(color = HailColors.Text, fontFamily = Manrope, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** What Hail reads and keeps, before the accessibility permission. */
@Composable
fun PrivacyScreen(onAgree: () -> Unit, onAppInfo: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("How Hail works", style = MaterialTheme.typography.headlineLarge)
        Muted("Hail needs Android's Accessibility permission to read prices in your ride apps. Here's exactly what that means.",
            Modifier.padding(top = 8.dp), 15)
        Spacer(Modifier.height(18.dp))
        listOf(
            "What it reads" to "Only Uber, Ola and Rapido, and only the fares, ETAs and route on the ride list. Android blocks it from every other app.",
            "When" to "Only while a ride request you started is running. You'll see a \"Hail is working\" notification with a Stop button.",
            "What's kept" to "Fare, ETA, ride type and surge for each option. Never screenshots, screen text, logins or driver details.",
            "Booking" to "Hail opens the app you pick with your trip filled in. You tap Book there. Hail never books or pays for you.",
            "Location and voice" to "Location is read once when you ask for a ride. Speech becomes text on your phone; Hail never gets audio.",
            "Saved places" to "Places you ♥ are kept on your Hail account so they're one tap away. Only you see them. Long-press to remove one, or delete everything in Profile.",
            "Price data (optional)" to "Only if you switch it on in Profile: the fares Hail finds, with your trip's neighbourhoods (about 1 km), never exact places, so Hail can predict the cheapest app. Switching it off deletes it.",
            "Usage stats" to "Hail counts things like how many rides were compared, to improve the app. Never your places, fares or what's on screen.",
        ).forEach { (t, b) ->
            Card(Modifier.padding(bottom = 10.dp)) {
                Text(t, style = MaterialTheme.typography.titleMedium)
                Muted(b, Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("I agree, turn it on", onClick = onAgree)
        Spacer(Modifier.height(10.dp))
        Muted("If Android says the setting is restricted: App info → ⋮ → Allow restricted settings, then try again.", size = 13)
        Text("Open App info", Modifier.fillMaxWidth().clickable(onClick = onAppInfo).padding(12.dp),
            color = HailColors.Soft, textAlign = TextAlign.Center, fontFamily = Manrope, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ConnectAppsScreen(apps: List<AppRow>, onToggle: (String, Boolean) -> Unit, onOpen: (String) -> Unit, onInstall: (String) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("Connect your apps", style = MaterialTheme.typography.headlineLarge)
        Muted("Log in once inside each ride app, the way you normally would. Hail uses those apps; it never sees your password or OTP.",
            Modifier.padding(top = 8.dp), 15)
        Spacer(Modifier.height(18.dp))
        AppsCard(apps, onToggle, onOpen, onInstall)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Done", enabled = apps.any { it.installed && it.enabled }, onClick = onDone)
        if (apps.none { it.installed }) Muted("Install at least one of Uber, Ola or Rapido to start.", Modifier.padding(top = 10.dp), 13)
    }
}
