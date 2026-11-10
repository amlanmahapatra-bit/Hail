package app.hail.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Microphone icon (not in the core icon set). */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("mic", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f); curveTo(10.3f, 2f, 9f, 3.3f, 9f, 5f); lineTo(9f, 11f)
            curveTo(9f, 12.7f, 10.3f, 14f, 12f, 14f); curveTo(13.7f, 14f, 15f, 12.7f, 15f, 11f)
            lineTo(15f, 5f); curveTo(15f, 3.3f, 13.7f, 2f, 12f, 2f); close()
        }
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 10.5f); curveTo(5f, 14.4f, 8.1f, 17.5f, 12f, 17.5f)
            curveTo(15.9f, 17.5f, 19f, 14.4f, 19f, 10.5f)
            moveTo(12f, 17.5f); lineTo(12f, 21.5f)
        }
    }.build()
}

/** Ride-app badge: a coloured letter, not the app's real logo. */
@Composable
fun AppBadge(app: String, size: Dp = 40.dp) {
    val (bg, fg) = when (app.lowercase()) {
        "uber" -> HailColors.Uber to HailColors.Bg
        "ola" -> HailColors.Ola to Color.White
        "rapido" -> HailColors.Rapido to HailColors.Bg
        else -> HailColors.SurfaceHigh to HailColors.Text
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(bg), contentAlignment = Alignment.Center) {
        Text(app.take(1).uppercase(), color = fg, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    gradient: Boolean = false,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.clip(shape)
    m = if (gradient) m.background(HailColors.gradient) else m.background(HailColors.Surface)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = HailColors.Muted,
)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 14, align: TextAlign? = null) =
    Text(text, modifier, color = HailColors.Muted, fontFamily = Manrope, fontSize = size.sp, lineHeight = (size * 1.45).sp, textAlign = align)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(HailColors.gradient).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = HailColors.Text) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(HailColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = HailColors.Text) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) HailColors.Text else HailColors.Surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = if (selected) HailColors.Bg else HailColors.Soft)
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        color = fg, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

@Composable
fun MicButton(size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(HailColors.gradient).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(MicIcon, "Speak", Modifier.size(size * 0.48f), tint = Color.White) }
}

@Composable
fun Tick(size: Dp = 24.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(HailColors.Mint), contentAlignment = Alignment.Center) {
        Text("✓", color = HailColors.Bg, fontWeight = FontWeight.Black, fontSize = (size.value * 0.55f).sp)
    }
}

@Composable
fun Row2(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) HailColors.Blue else HailColors.SurfaceHigh).clickable { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun Outline(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, HailColors.SurfaceHigh, RoundedCornerShape(20.dp)).padding(16.dp), content = content)
