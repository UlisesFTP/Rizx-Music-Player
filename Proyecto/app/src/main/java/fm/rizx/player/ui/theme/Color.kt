package fm.rizx.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The full Rizx token palette, mirroring the CSS custom properties in the source
 * design (`RizxScreens.dc.html`). Two instances exist: [RizxDarkColors] (Direction A —
 * Ivory) and [RizxLightColors] (Direction B — Paper). Screens read these via
 * [LocalRizxColors] / `RizxTheme.colors`, never hard-coded hex values.
 */
@Immutable
data class RizxColors(
    val isDark: Boolean,
    val bg: Color,
    val elev: Color,
    val elev2: Color,
    val inset: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val text2: Color,
    val muted: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val onFill: Color,
    val navBg: Color,
    val rowHover: Color,
    val hatch: Color,
    val coverInitial: Color,
    val placeholderA: Color,
    val placeholderB: Color,
    val heroA: Color,
    val heroB: Color,
    val heroText: Color,
    val heroSub: Color,
    val heroLetter: Color,
    val artLabel: Color,
    val homeInd: Color,
    val waveTrack: Color,
    // Brutalist structure: a thick "ink" border and a hard (non-blurred) offset drop-shadow color,
    // plus the dot-matrix pair (base/lit) for the Nothing-OS dot fields, grids and numeral glow.
    val hardLine: Color,
    val shadowHard: Color,
    val dot: Color,
    val dotOn: Color,
    // Vivid RED accent (UESC/DOOM) — used sparingly for active state, playhead, 'like', markers/alerts.
    // `onRed` is the legible text/icon colour on top of a red fill.
    val redAccent: Color,
    val onRed: Color,
    // Elevation/shadow strength. Ivory is glow-restrained (mostly flat); Paper uses
    // real dim drop-shadows instead of glows.
    val cardElevation: Dp,
    val artElevation: Dp,
    val floatElevation: Dp,
    val navElevation: Dp,
) {
    /** Primary fill (buttons, active chips) equals the accent. */
    val fill: Color get() = accent
}

/** Aurora "snake lights" palette sampled from the album cover (Now Playing, dark only). */
val AuroraPalette = listOf(
    Color(0xFF7C3FC4), // violet
    Color(0xFFC4477F), // magenta
    Color(0xFFEF6A3A), // coral
    Color(0xFF5638B0), // deep indigo
)

/** Default dynamic Now-Playing accent when album art is present (coral). */
val NowPlayingAccent = Color(0xFFEF6A3C)

// ----------------------------------------------------------------------------
// Direction A — Ivory (dark): sharp, glow-restrained.
// ----------------------------------------------------------------------------
val RizxDarkColors = RizxColors(
    isDark = true,
    bg = Color(0xFF000000),        // pitch black (AMOLED)
    elev = Color(0xFF161310),      // warm cards, raised off pure black
    elev2 = Color(0xFF201C16),
    inset = Color(0xFF000000),
    line = Color.White.copy(alpha = 0.08f),
    line2 = Color.White.copy(alpha = 0.14f),
    text = Color(0xFFEDE8DD),      // cream
    text2 = Color(0xFFB9B2A5),
    muted = Color(0xFF8B847A),
    dim = Color(0xFF5E584E),
    accent = Color(0xFFF3EFE2),    // warm cream (primary fill)
    accent2 = Color(0xFFFFFFFF),
    onFill = Color(0xFF000000),
    navBg = Color(0xFF0B0908).copy(alpha = 0.96f),
    rowHover = Color.White.copy(alpha = 0.04f),
    hatch = Color.White.copy(alpha = 0.05f),
    coverInitial = Color.White.copy(alpha = 0.12f),
    placeholderA = Color(0xFF282219),
    placeholderB = Color(0xFF16120D),
    heroA = Color(0xFF14110C),
    heroB = Color(0xFF000000),
    heroText = Color(0xFFFDF8EE),
    heroSub = Color.White.copy(alpha = 0.55f),
    heroLetter = Color.White.copy(alpha = 0.08f),
    artLabel = Color.White.copy(alpha = 0.5f),
    homeInd = Color.White.copy(alpha = 0.22f),
    waveTrack = Color.White.copy(alpha = 0.28f),
    hardLine = Color.White.copy(alpha = 0.26f),
    shadowHard = Color.Black,
    dot = Color.White.copy(alpha = 0.10f),
    dotOn = Color(0xFFEDE8DD).copy(alpha = 0.85f),
    redAccent = Color(0xFFFF3B2F),
    onRed = Color(0xFFFFF7F4),
    cardElevation = 0.dp,
    artElevation = 0.dp,
    floatElevation = 6.dp,
    navElevation = 0.dp,
)

// ----------------------------------------------------------------------------
// Direction B — Paper (light): warm ivory paper, black ink, dim drop-shadows.
// ----------------------------------------------------------------------------
val RizxLightColors = RizxColors(
    isDark = false,
    bg = Color(0xFFEAE5DB),
    elev = Color(0xFFF0EEE6),
    elev2 = Color(0xFFF7F6F1),
    inset = Color(0xFFE1DDD1),
    line = Color(0xFF221F1A).copy(alpha = 0.12f),
    line2 = Color(0xFF221F1A).copy(alpha = 0.20f),
    text = Color(0xFF201C16),
    text2 = Color(0xFF56534C),
    muted = Color(0xFF888379),
    dim = Color(0xFFADA99B),
    accent = Color(0xFF191510), // ink
    accent2 = Color(0xFF191510),
    onFill = Color(0xFFEFECE5),
    navBg = Color(0xFFF3F0E9), // opaque paper surface (no transparency)
    rowHover = Color(0xFF221F1A).copy(alpha = 0.05f),
    hatch = Color(0xFF221F1A).copy(alpha = 0.085f),
    coverInitial = Color(0xFF1C1914).copy(alpha = 0.16f),
    placeholderA = Color(0xFFDFD9CA),
    placeholderB = Color(0xFFCCC2AE),
    heroA = Color(0xFFDBD6C7),
    heroB = Color(0xFFC9C2AE),
    heroText = Color(0xFF221D16),
    heroSub = Color(0xFF221E18).copy(alpha = 0.60f),
    heroLetter = Color(0xFF1C1914).copy(alpha = 0.09f),
    artLabel = Color(0xFF1C1914).copy(alpha = 0.5f),
    homeInd = Color(0xFF1C1914).copy(alpha = 0.26f),
    waveTrack = Color(0xFF221F1A).copy(alpha = 0.24f),
    hardLine = Color(0xFF191510), // ink — full-strength brutalist border
    shadowHard = Color(0xFF17110A), // warm near-black offset shadow (matches PaperShadowColor)
    dot = Color(0xFF221F1A).copy(alpha = 0.17f),
    dotOn = Color(0xFF191510).copy(alpha = 0.92f),
    redAccent = Color(0xFFDE2A1E),
    onRed = Color(0xFFFFF7F4),
    cardElevation = 7.dp,
    artElevation = 9.dp,
    floatElevation = 10.dp,
    navElevation = 8.dp,
)
