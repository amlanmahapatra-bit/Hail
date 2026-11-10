package app.hail.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hail.android.RideCategory
import org.json.JSONObject
import java.time.LocalTime

/** What the Home screen needs, so it can be rendered without a phone (screenshots). */
data class HomeState(
    val category: String?,
    val favorites: List<JSONObject>,
    val savedThisMonth: Int,
    val ridesThisMonth: Int,
    val setupWarning: String?,
    val testMode: Boolean,
)

@Composable
fun HomeScreen(
    s: HomeState,
    onAsk: (String) -> Unit,
    onMic: () -> Unit,
    onCategory: (String?) -> Unit,
    onFavorite: (JSONObject) -> Unit,
    onRemoveFavorite: (JSONObject) -> Unit,
    onFixSetup: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Muted(greeting(), size = 15)
        Text("Where to?", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        AskBar("Say or type a place…", onAsk, onMic)
        Spacer(Modifier.height(16.dp))
        CategoryChips(s.category, onCategory)

        if (s.setupWarning != null) {
            Spacer(Modifier.height(16.dp))
            Card(onClick = onFixSetup) {
                Row2 {
                    Column(Modifier.weight(1f)) {
                        Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                        Muted(s.setupWarning, Modifier.padding(top = 4.dp))
                    }
                    Pill("Fix", HailColors.Amber, HailColors.AmberBg)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Saved places")
        Spacer(Modifier.height(10.dp))
        if (s.favorites.isEmpty()) {
            Muted("Tap ♡ on a result to save a place here. Then it's one tap next time.")
        } else {
            FavoritesGrid(s.favorites, onFavorite, onRemoveFavorite)
        }

        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth(), gradient = true, padding = 18.dp) {
            Text("SAVED THIS MONTH", style = MaterialTheme.typography.labelSmall, color = HailColors.Text.copy(alpha = 0.85f))
            Text(rupees(s.savedThisMonth.toDouble()), fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp)
            Text(
                if (s.ridesThisMonth == 0) "Your savings show up here after your first ride."
                else "across ${s.ridesThisMonth} ride${if (s.ridesThisMonth == 1) "" else "s"} · every app checked each time",
                fontFamily = Manrope, fontSize = 13.sp, color = HailColors.Text.copy(alpha = 0.9f),
            )
        }
        if (s.testMode) {
            Spacer(Modifier.height(12.dp))
            Pill("TEST MODE · simulated prices", HailColors.Amber, HailColors.AmberBg)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AskBar(hint: String, onAsk: (String) -> Unit, onMic: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp)).background(HailColors.Surface)
            .padding(start = 18.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Muted(hint, size = 16)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = HailColors.Text, fontFamily = Manrope, fontSize = 16.sp),
                cursorBrush = SolidColor(HailColors.Violet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) { onAsk(text); text = "" } }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        MicButton(onClick = onMic)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(selected: String?, onCategory: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Any", selected == null) { onCategory(null) }
        RideCategory.entries.forEach { c -> Chip(c.label, selected == c.key) { onCategory(c.key) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesGrid(favs: List<JSONObject>, onTap: (JSONObject) -> Unit, onLong: (JSONObject) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        favs.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { f ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(HailColors.Surface)
                            .combinedClickable(onClick = { onTap(f) }, onLongClick = { onLong(f) }).padding(16.dp),
                    ) {
                        Text("♥ " + f.optString("label"), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Muted("Tap to go · hold to remove", size = 12)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
