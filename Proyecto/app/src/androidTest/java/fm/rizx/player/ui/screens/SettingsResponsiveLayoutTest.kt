package fm.rizx.player.ui.screens

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fm.rizx.player.ui.components.EditorialSelect
import fm.rizx.player.ui.theme.RizxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for a language label that previously consumed the row's text column. */
@RunWith(AndroidJUnit4::class)
class SettingsResponsiveLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun language_control_keeps_its_text_and_long_value_visible_on_a_compact_screen_with_large_font() {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = 320 }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                RizxTheme(darkTheme = false) {
                    ControlRow(
                        title = "Idioma",
                        caption = "Idioma de la interfaz",
                        stackOnPhone = true,
                    ) {
                        EditorialSelect("Predeterminado del sistema") {}
                    }
                }
            }
        }

        rule.onNodeWithText("Idioma").assertIsDisplayed()
        rule.onNodeWithText("Idioma de la interfaz").assertIsDisplayed()
        rule.onNodeWithText("Predeterminado del sistema").assertIsDisplayed()
    }
}
