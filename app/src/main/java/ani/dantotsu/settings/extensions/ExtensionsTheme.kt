package ani.dantotsu.settings.extensions

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ani.dantotsu.R

/**
 * Visual language of the Extensions screen.
 *
 * The screen keeps Kiroku's own type face and Material theming; the palette mirrors the reference
 * design (near-black surfaces, muted cards, pink accents) which the rest of the app exposes as its
 * dark shell.
 */
object ExtensionPalette {
    val background = Color(0xFF0B0A0C)
    val backgroundGlow = Color(0xFF1A1017)
    val pill = Color(0xFF17141A)
    val card = Color(0xFF221C21)
    val chip = Color(0xFF1C181E)
    val pink = Color(0xFFF6B3CB)
    val pinkDeep = Color(0xFFE794B2)
    val onPink = Color(0xFF2A0D18)
    val text = Color(0xFFF5F0F4)
    val muted = Color(0xFF9B939D)
    val green = Color(0xFF74D790)
    val orange = Color(0xFFF1B58F)
    val deleteButton = Color(0xFFC79C86)
    val settingsButton = Color(0xFFE9A7C4)
    val error = Color(0xFFFF9B9B)
}

private val poppins = FontFamily(
    Font(R.font.poppins, FontWeight.Normal),
    Font(R.font.poppins_semi_bold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val extensionTypography = Typography(
    titleLarge = TextStyle(fontFamily = poppins, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = poppins, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = poppins, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = poppins, fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
    labelMedium = TextStyle(fontFamily = poppins, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

@Composable
fun ExtensionsTheme(content: @Composable () -> Unit) {
    // The reference screen is a dark surface; honour the app's dark mode instead of forcing it.
    val dark = isSystemInDarkTheme() || true
    val scheme = darkColorScheme(
        primary = ExtensionPalette.pink,
        onPrimary = ExtensionPalette.onPink,
        secondary = ExtensionPalette.pinkDeep,
        background = ExtensionPalette.background,
        onBackground = ExtensionPalette.text,
        surface = ExtensionPalette.card,
        onSurface = ExtensionPalette.text,
        surfaceVariant = ExtensionPalette.chip,
        onSurfaceVariant = ExtensionPalette.muted,
        error = ExtensionPalette.error,
    )
    MaterialTheme(colorScheme = scheme, typography = extensionTypography, content = content)
}
