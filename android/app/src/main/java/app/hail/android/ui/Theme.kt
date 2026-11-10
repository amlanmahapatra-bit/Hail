package app.hail.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.hail.android.R

/** Hail's look: deep navy, a blue-to-violet gradient, mint for savings. Same as the deck. */
object HailColors {
    val Bg = Color(0xFF0E0F1E)
    val Surface = Color(0xFF1A1C33)
    val SurfaceHigh = Color(0xFF23264A)
    val Nav = Color(0xFF12142A)
    val Text = Color(0xFFF2F3FF)
    val Muted = Color(0xFF9396B8)
    val Soft = Color(0xFFC9CBEA)
    val Blue = Color(0xFF5B6CFF)
    val Violet = Color(0xFFA07BFF)
    val Mint = Color(0xFF3DDC97)
    val MintBg = Color(0xFF14382B)
    val Amber = Color(0xFFFFC857)
    val AmberBg = Color(0xFF3A2F12)
    val Danger = Color(0xFFFF6B6B)
    val Uber = Color(0xFFF2F3FF)
    val Ola = Color(0xFF2BB673)
    val Rapido = Color(0xFFFFD23F)

    val gradient = Brush.linearGradient(listOf(Blue, Violet))
}

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private val type = Typography(
    displayLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp),
)

@Composable
fun HailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = HailColors.Bg,
            surface = HailColors.Surface,
            primary = HailColors.Blue,
            secondary = HailColors.Violet,
            onBackground = HailColors.Text,
            onSurface = HailColors.Text,
            onPrimary = HailColors.Text,
        ),
        typography = type,
        content = content,
    )
}
