package vn.nghetruyen.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5856D6),
    secondary = Color(0xFF007AFF),
    tertiary = Color(0xFFAF52DE),
    background = Color(0xFFF0F2F5),
    surface = Color.White,
    error = Color(0xFFD70015),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8B6FF),
    secondary = Color(0xFF75B8FF),
    tertiary = Color(0xFFE0A6FF),
)

@Composable
fun NgheTruyenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
