@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package fm.rizx.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fm.rizx.player.R

// Variable fonts bundled under res/font (OFL). Each weight maps the `wght` axis so the
// correct instance renders on API 26+ (the project minSdk).
// DM Sans — the display face, shared with Rizx Web (OFL, variable). Its `opsz` axis (9–40) is what a
// browser sets from the font size automatically; Compose fixes the axes per Font, so the family comes
// in three optical sizes and [sg] picks the one nearest the size it is asked for.
private fun dmFont(weight: FontWeight, axis: Int, opticalSize: Float) =
    Font(
        R.font.dm_sans,
        weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(axis), FontVariation.Setting("opsz", opticalSize)),
    )

private fun dmFamily(opticalSize: Float) = FontFamily(
    dmFont(FontWeight.Normal, 400, opticalSize),
    dmFont(FontWeight.Medium, 500, opticalSize),
    dmFont(FontWeight.SemiBold, 600, opticalSize),
    dmFont(FontWeight.Bold, 700, opticalSize),
)

private fun mrFont(weight: FontWeight, axis: Int) =
    Font(R.font.manrope, weight, variationSettings = FontVariation.Settings(FontVariation.weight(axis)))

// Martian Mono — technical monospace for UI / labels / body (OFL, variable). The `wdth` axis is
// narrowed (~88) so the wide mono glyphs stay legible and don't overflow rows/chips.
private fun mmFont(weight: FontWeight, axis: Int, width: Float = 88f) =
    Font(
        R.font.martian_mono,
        weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(axis), FontVariation.Setting("wdth", width)),
    )

// Doto — dot-matrix display face (OFL). `ROND` (0=square LED dots … 100=round) is pinned high so
// numerals read like a Nothing-OS glyph matrix; the `wght` axis maps to the requested weight.
private fun dotFont(weight: FontWeight, axis: Int, round: Float = 100f) =
    Font(
        R.font.doto,
        weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(axis), FontVariation.Setting("ROND", round)),
    )

/** DM Sans at text optical size — labels and small headings (≤ 18sp). */
val DmSansText = dmFamily(14f)

/** DM Sans at a middle optical size — section titles (19–30sp). */
val DmSansTitle = dmFamily(24f)

/** DM Sans at display optical size — the giant editorial titles (> 30sp), as the web renders them. */
val DmSansDisplay = dmFamily(40f)

/** The display family for a given size, mirroring the browser's automatic optical sizing. */
fun displayFamilyFor(size: Int): FontFamily = when {
    size <= 18 -> DmSansText
    size <= 30 -> DmSansTitle
    else -> DmSansDisplay
}

/** Manrope — legacy body face (kept for easy revert; `mr()` now renders Martian Mono). */
val Manrope = FontFamily(
    mrFont(FontWeight.Normal, 400),
    mrFont(FontWeight.Medium, 500),
    mrFont(FontWeight.SemiBold, 600),
    mrFont(FontWeight.Bold, 700),
    mrFont(FontWeight.ExtraBold, 800),
)

/** Martian Mono — the technical monospace that now drives all body / UI / label text. */
val MartianMono = FontFamily(
    mmFont(FontWeight.Normal, 400),
    mmFont(FontWeight.Medium, 500),
    mmFont(FontWeight.SemiBold, 600),
    mmFont(FontWeight.Bold, 700),
)

/** Doto — dot-matrix numerals / short labels (clock, "N of M" counter, eyebrow labels). */
val Doto = FontFamily(
    dotFont(FontWeight.Medium, 500),
    dotFont(FontWeight.Bold, 700),
    dotFont(FontWeight.Black, 900),
)

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Display style — **DM Sans** since 2026-09-09 (the owner's call: the phone's titles should be the web's).
 * The function keeps its historical name so no call site had to move; sizes/letter-spacing mirror the
 * design's inline `font:` declarations.
 */
fun sg(
    size: Int,
    weight: FontWeight = FontWeight.Bold,
    letterSpacingEm: Float = -0.01f,
    lineHeight: Int = 0,
): TextStyle = TextStyle(
    fontFamily = displayFamilyFor(size),
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = letterSpacingEm.em,
    lineHeight = if (lineHeight > 0) lineHeight.sp else TextStyle.Default.lineHeight,
    lineHeightStyle = TightLineHeight,
)

/**
 * Body / UI style — now **Martian Mono** (technical monospace) instead of Manrope, so the whole app
 * inherits the industrial "spec-sheet" look from one place. Slightly negative default tracking tightens
 * the wide mono glyphs. `[mono]` is a synonym for new call sites that want to read as monospace.
 */
fun mr(
    size: Int,
    weight: FontWeight = FontWeight.Medium,
    letterSpacingEm: Float = -0.01f,
    lineHeight: Int = 0,
): TextStyle = TextStyle(
    fontFamily = MartianMono,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = letterSpacingEm.em,
    lineHeight = if (lineHeight > 0) lineHeight.sp else TextStyle.Default.lineHeight,
    lineHeightStyle = TightLineHeight,
)

/** Semantic alias of [mr] for new monospace call sites. */
fun mono(
    size: Int,
    weight: FontWeight = FontWeight.Medium,
    letterSpacingEm: Float = -0.01f,
    lineHeight: Int = 0,
): TextStyle = mr(size, weight, letterSpacingEm, lineHeight)

/** Technical code/eyebrow label — mono, small, wide tracking, meant for UPPERCASE (serial/batch/HUD). */
fun code(
    size: Int = 10,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacingEm: Float = 0.1f,
): TextStyle = mr(size, weight, letterSpacingEm)

/**
 * Doto dot-matrix style for numerals and short uppercase labels. Positive tracking spaces the
 * dot glyphs so the matrix reads clearly; defaults to Bold.
 */
fun dot(
    size: Int,
    weight: FontWeight = FontWeight.Bold,
    letterSpacingEm: Float = 0.04f,
    lineHeight: Int = 0,
): TextStyle = TextStyle(
    fontFamily = Doto,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = letterSpacingEm.em,
    lineHeight = if (lineHeight > 0) lineHeight.sp else TextStyle.Default.lineHeight,
    lineHeightStyle = TightLineHeight,
)

/** Minimal Material3 typography (Manrope-based) so MaterialTheme has sane defaults. */
val RizxTypography = Typography(
    titleLarge = sg(22, FontWeight.Bold, -0.02f),
    titleMedium = sg(19, FontWeight.Bold, -0.01f),
    bodyLarge = mr(15, FontWeight.Medium),
    bodyMedium = mr(14, FontWeight.Medium),
    bodySmall = mr(12, FontWeight.Medium),
    labelLarge = mr(13, FontWeight.SemiBold),
    labelMedium = mr(12, FontWeight.SemiBold),
    labelSmall = mr(10, FontWeight.Medium),
)
