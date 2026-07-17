package fm.rizx.player.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalRizxColors = staticCompositionLocalOf<RizxColors> {
    error("RizxColors not provided — wrap content in RizxTheme { }")
}

/** Ambient accessor: `RizxTheme.colors.text`, etc. */
object RizxTheme {
    val colors: RizxColors
        @Composable @ReadOnlyComposable get() = LocalRizxColors.current
}

@Composable
fun RizxTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) RizxDarkColors else RizxLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Bridge to a Material3 scheme so ripples, text selection and any stray Material
    // component inherit sensible colors. Screens themselves read RizxTheme.colors.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onFill,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.elev,
            onSurface = colors.text,
            surfaceVariant = colors.elev2,
            onSurfaceVariant = colors.text2,
            outline = colors.line2,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onFill,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.elev,
            onSurface = colors.text,
            surfaceVariant = colors.elev2,
            onSurfaceVariant = colors.text2,
            outline = colors.line2,
        )
    }

    CompositionLocalProvider(LocalRizxColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = RizxTypography,
            content = content,
        )
    }
}
