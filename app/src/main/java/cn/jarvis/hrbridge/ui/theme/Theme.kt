package cn.jarvis.hrbridge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = HrGreen,
    onPrimary = DarkBg,
    primaryContainer = Color32(HrGreen),
    secondary = HrBlue,
    tertiary = HrPurple,
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = DarkMuted,
    outline = DarkOutline,
    error = HrRed
)

private val LightColors = lightColorScheme(
    primary = HrGreen,
    onPrimary = LightBg,
    secondary = HrBlue,
    tertiary = HrPurple,
    background = LightBg,
    onBackground = LightOnBg,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightMuted,
    outline = LightOutline,
    error = HrRed
)

/** 占位：避免 containerColor 过亮。后续如需微调整色可改此函数。 */
private fun Color32(c: androidx.compose.ui.graphics.Color) = c.copy(alpha = 0.18f)

@Composable
fun HRBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HRBridgeTypography,
        content = content
    )
}
