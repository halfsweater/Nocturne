package app.nocturne.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Nocturne {
    val Charcoal = Color(0xFF121316)
    val Surface = Color(0xFF1A1C20)
    val SurfaceHigh = Color(0xFF23262B)
    val Hairline = Color(0xFF2E3137)
    val Silver = Color(0xFFB9BDC4)
    val SilverDim = Color(0xFF7D828A)
    val Ivory = Color(0xFFF4EFE4)
    val Accent = Color(0xFFD9C7A3)      // warm brass, used sparingly (favourites, pedal)
    val Danger = Color(0xFFE08A7A)

    val Display = FontFamily.Serif
    val Body = FontFamily.SansSerif
}

private val colors = darkColorScheme(
    primary = Nocturne.Silver,
    onPrimary = Nocturne.Charcoal,
    secondary = Nocturne.Accent,
    onSecondary = Nocturne.Charcoal,
    background = Nocturne.Charcoal,
    onBackground = Nocturne.Ivory,
    surface = Nocturne.Surface,
    onSurface = Nocturne.Ivory,
    surfaceVariant = Nocturne.SurfaceHigh,
    onSurfaceVariant = Nocturne.Silver,
    outline = Nocturne.Hairline,
    error = Nocturne.Danger,
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Nocturne.Display, fontWeight = FontWeight.Normal, fontSize = 30.sp, letterSpacing = 0.2.sp),
    headlineSmall = TextStyle(fontFamily = Nocturne.Display, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = Nocturne.Display, fontWeight = FontWeight.Normal, fontSize = 18.sp),
    bodyMedium = TextStyle(fontFamily = Nocturne.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Nocturne.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = Nocturne.Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.6.sp),
    labelSmall = TextStyle(fontFamily = Nocturne.Body, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.4.sp),
)

@Composable
fun NocturneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
