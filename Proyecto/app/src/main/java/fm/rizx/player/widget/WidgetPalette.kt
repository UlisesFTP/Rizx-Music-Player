package fm.rizx.player.widget

import fm.rizx.player.R
import fm.rizx.player.domain.model.ThemeMode

/**
 * The Rizx palette (`ui/theme/Color.kt`) as ARGB ints and drawable resources, because RemoteViews
 * cannot read Compose colors. Two looks: the app's dark "Ivory" and light "Paper" directions.
 */
data class WidgetPalette(
    val isDark: Boolean,
    val text: Int,
    val text2: Int,
    val muted: Int,
    /** The solid block colour (play, the microphone pill) and what sits on it. */
    val fill: Int,
    val onFill: Int,
    val red: Int,
    val onRed: Int,
    val dotOn: Int,
    val dotOff: Int,
    val cardRes: Int,
    val squareRes: Int,
    val fillRes: Int,
    val pillRes: Int,
    val likedRes: Int,
) {
    companion object {
        val Dark = WidgetPalette(
            isDark = true,
            text = 0xFFEDE8DD.toInt(),
            text2 = 0xFFB9B2A5.toInt(),
            muted = 0xFF8B847A.toInt(),
            fill = 0xFFF3EFE2.toInt(),
            onFill = 0xFF000000.toInt(),
            red = 0xFFFF3B2F.toInt(),
            onRed = 0xFFFFF7F4.toInt(),
            dotOn = 0xFFEDE8DD.toInt(),
            dotOff = 0x40FFFFFF,
            cardRes = R.drawable.widget_card_dark,
            squareRes = R.drawable.widget_square_dark,
            fillRes = R.drawable.widget_fill_dark,
            pillRes = R.drawable.widget_pill_dark,
            likedRes = R.drawable.widget_liked_dark,
        )

        val Light = WidgetPalette(
            isDark = false,
            text = 0xFF201C16.toInt(),
            text2 = 0xFF56534C.toInt(),
            muted = 0xFF888379.toInt(),
            fill = 0xFF191510.toInt(),
            onFill = 0xFFEFECE5.toInt(),
            red = 0xFFDE2A1E.toInt(),
            onRed = 0xFFFFF7F4.toInt(),
            dotOn = 0xFF191510.toInt(),
            dotOff = 0x2E221F1A,
            cardRes = R.drawable.widget_card_light,
            squareRes = R.drawable.widget_square_light,
            fillRes = R.drawable.widget_fill_light,
            pillRes = R.drawable.widget_pill_light,
            likedRes = R.drawable.widget_liked_light,
        )

        /** The app's own choice wins; SYSTEM follows the device, exactly like `MainActivity` does. */
        fun resolve(mode: ThemeMode, systemDark: Boolean): WidgetPalette = when (mode) {
            ThemeMode.DARK -> Dark
            ThemeMode.LIGHT -> Light
            ThemeMode.SYSTEM -> if (systemDark) Dark else Light
        }
    }
}
