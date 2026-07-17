package fm.rizx.player.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Diagonal "tooth" hatch overlay used on every placeholder/cover in the design
 * (`repeating-linear-gradient(135deg, hatch 0 6px, transparent 6px 12px)`).
 */
fun Modifier.hatch(
    color: Color,
    stripe: Dp = 6.dp,
    gap: Dp = 6.dp,
): Modifier = drawBehind {
    val sw = stripe.toPx()
    val periodAlongAxis = (stripe + gap).toPx()
    val step = periodAlongAxis * sqrt(2f) // perpendicular spacing -> delta of (x+y)
    clipRect {
        val max = size.width + size.height
        var c = 0f
        while (c <= max) {
            drawLine(
                color = color,
                start = Offset(c, 0f),
                end = Offset(0f, c),
                strokeWidth = sw,
            )
            c += step
        }
    }
}

// Cover/art tint hues (cycled by index), matching the design's `hues` array.
private val Hues = listOf(
    Color(0xFFE0719A),
    Color(0xFF6D7CF0),
    Color(0xFFE8A24A),
    Color(0xFF4EC5A8),
    Color(0xFFD96B6B),
    Color(0xFF9B7BE8),
    Color(0xFF5AB0E0),
)

private fun hueOf(index: Int): Color = Hues[((index % Hues.size) + Hues.size) % Hues.size]

/**
 * Per-index cover gradient. Dark mixes the hue into near-black; light mixes a lighter
 * tint into warm paper. Approximates the design's `color-mix(in oklab, …)` with an sRGB lerp.
 */
fun coverTint(index: Int, isDark: Boolean): Brush {
    val hue = hueOf(index)
    return if (isDark) {
        Brush.linearGradient(listOf(lerp(Color(0xFF141417), hue, 0.22f), Color(0xFF0B0B0D)))
    } else {
        Brush.linearGradient(listOf(lerp(Color(0xFFE1DCCE), hue, 0.13f), Color(0xFFD1CBBB)))
    }
}

/** Per-index solid background for the "Browse all" category tiles. */
fun catBg(index: Int, isDark: Boolean): Color {
    val hue = hueOf(index)
    return if (isDark) lerp(Color(0xFF121215), hue, 0.11f) else lerp(Color(0xFFEAE5DA), hue, 0.09f)
}

/** The generic placeholder gradient (avatars, mini-player thumb). */
fun placeholderBrush(colors: RizxColors): Brush =
    Brush.linearGradient(listOf(colors.placeholderA, colors.placeholderB))

/** Hero / album-art base gradient. */
fun heroBrush(colors: RizxColors): Brush =
    Brush.linearGradient(listOf(colors.heroA, colors.heroB))

// ----------------------------------------------------------------------------
// Paper-theme physicality: warm-dark drop-shadows + a subtle paper grain.
// Both are no-ops on Ivory (dark) so the dark theme stays flat / glow-restrained.
// ----------------------------------------------------------------------------

/** Warm near-black that tints Paper drop-shadows (darker/warmer than the default black). */
private val PaperShadowColor = Color(0xFF17110A)

/**
 * Theme-aware drop shadow. On Paper (light) it casts a dim, warm-dark shadow at the given
 * [elevation] token; on Ivory (dark) it renders nothing. Defaults to `cardElevation`.
 */
@Composable
fun Modifier.paperElevation(
    shape: Shape = RectangleShape,
    elevation: Dp = RizxTheme.colors.cardElevation,
): Modifier = if (elevation.value > 0f) {
    shadow(elevation, shape, clip = false, ambientColor = PaperShadowColor, spotColor = PaperShadowColor)
} else this

private val PaperGrainInk = Color(0xFF3A3120)
private val PaperGridInk = Color(0xFF2A2316)
private val PaperVignette = Color(0xFF1C1710)

/**
 * Paper texture for the Paper (light) theme: a fine **quadrille grid** (small squares, like
 * graph paper — stronger major lines every 5th) + warm grain speckle + a soft vignette,
 * painted behind the content. The whole texture is rendered once into a cached bitmap
 * (deterministic seed), so it costs a single image draw per frame. No-op on Ivory (dark).
 */
fun Modifier.paperTexture(colors: RizxColors): Modifier {
    if (colors.isDark) return this
    return drawWithCache {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val tex = ImageBitmap(w, h)
        val canvas = Canvas(tex)

        // Fine "cuadrícula" — small square cells, with slightly stronger lines every 5th.
        val cell = 18.dp.toPx()
        val minor = Paint().apply { color = PaperGridInk.copy(alpha = 0.07f); strokeWidth = 1f }
        val major = Paint().apply { color = PaperGridInk.copy(alpha = 0.11f); strokeWidth = 1.5f }
        var n = 0
        var x = 0f
        while (x <= w) {
            canvas.drawLine(Offset(x, 0f), Offset(x, h.toFloat()), if (n % 5 == 0) major else minor)
            x += cell; n++
        }
        n = 0
        var y = 0f
        while (y <= h) {
            canvas.drawLine(Offset(0f, y), Offset(w.toFloat(), y), if (n % 5 == 0) major else minor)
            y += cell; n++
        }

        // Warm grain speckle over the grid.
        val grain = Paint()
        val rnd = Random(7)
        val specks = (w * h / 1200).coerceIn(400, 7000)
        repeat(specks) {
            val ink = rnd.nextFloat() < 0.55f
            grain.color = (if (ink) PaperGrainInk else Color.White)
                .copy(alpha = if (ink) 0.025f + rnd.nextFloat() * 0.05f else 0.035f + rnd.nextFloat() * 0.05f)
            canvas.drawCircle(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h), 0.5f + rnd.nextFloat() * 1.1f, grain)
        }

        onDrawBehind {
            drawImage(tex)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, PaperVignette.copy(alpha = 0.06f)),
                    center = Offset(size.width / 2f, size.height * 0.4f),
                    radius = size.maxDimension * 0.72f,
                ),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Brutalist / Nothing-OS decorators.
// ----------------------------------------------------------------------------

/**
 * Hard **offset drop-shadow** (neobrutalism): a solid, non-blurred copy of [shape] painted [offset]
 * down-right behind the content. Unlike [paperElevation] (a soft blurred shadow) this reads as a
 * crisp block. Apply to elements with an opaque background; leave [offset] of space to the
 * bottom-right so the shadow isn't clipped by a neighbour.
 */
fun Modifier.brutalShadow(
    color: Color,
    offset: Dp = 5.dp,
    shape: Shape = RectangleShape,
): Modifier = drawBehind {
    val o = offset.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(left = o, top = o) { drawOutline(outline, color = color) }
}

/**
 * Static **dot-matrix** background: a regular grid of dots painted behind the content (Nothing-OS
 * texture). Cheap (drawn each frame but just circles); for a moving field use [DotMatrixField].
 */
fun Modifier.dotGrid(
    color: Color,
    spacing: Dp = 16.dp,
    dotRadius: Dp = 1.2.dp,
): Modifier = drawBehind {
    val s = spacing.toPx()
    if (s <= 0f) return@drawBehind
    val r = dotRadius.toPx()
    var y = s / 2f
    while (y < size.height) {
        var x = s / 2f
        while (x < size.width) {
            drawCircle(color = color, radius = r, center = Offset(x, y))
            x += s
        }
        y += s
    }
}

/**
 * One-shot **staggered entrance**: fade + short upward translate, delayed by [index] so a list
 * reveals row-by-row (Nothing-OS mechanical cascade). Runs once when the item first composes.
 */
fun Modifier.staggeredReveal(
    index: Int,
    itemDelayMs: Int = 38,
    durationMs: Int = 300,
    maxDelayMs: Int = 380,
): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    val p by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = durationMs,
            delayMillis = (index.coerceAtLeast(0) * itemDelayMs).coerceAtMost(maxDelayMs),
            easing = FastOutSlowInEasing,
        ),
        label = "staggerReveal",
    )
    LaunchedEffect(Unit) { shown = true }
    graphicsLayer {
        alpha = p
        translationY = (1f - p) * 22.dp.toPx()
    }
}

// ----------------------------------------------------------------------------
// Industrial / HUD "spec-sheet" chrome (refs #2/#4). All static (drawBehind) — no per-frame animation,
// so they never contend with audio decoding on weak hardware.
// ----------------------------------------------------------------------------

/**
 * HUD **corner brackets** (⌐ ¬ L ⌐) just inside the element's bounds — the technical framing from ref #4.
 * [len] = each arm's length, [inset] pushes them off the edge. Pair with a border/background.
 */
fun Modifier.cornerBrackets(
    color: Color,
    len: Dp = 10.dp,
    thickness: Dp = 1.5.dp,
    inset: Dp = 3.dp,
): Modifier = drawBehind {
    val l = len.toPx()
    val t = thickness.toPx()
    val o = inset.toPx() + t / 2f
    val w = size.width
    val ht = size.height
    fun hLine(x: Float, y: Float, dx: Float) = drawLine(color, Offset(x, y), Offset(x + dx, y), t)
    fun vLine(x: Float, y: Float, dy: Float) = drawLine(color, Offset(x, y), Offset(x, y + dy), t)
    hLine(o, o, l); vLine(o, o, l)                              // top-left
    hLine(w - o - l, o, l); vLine(w - o, o, l)                  // top-right
    hLine(o, ht - o, l); vLine(o, ht - o - l, l)               // bottom-left
    hLine(w - o - l, ht - o, l); vLine(w - o, ht - o - l, l)    // bottom-right
}

/**
 * Technical **blueprint grid** (ref #2): a fine square grid with small crosshair "+" registration marks
 * at every [major]-th intersection — the background chrome. Drawn on recomposition (not per frame).
 */
fun Modifier.blueprintGrid(
    color: Color,
    cell: Dp = 26.dp,
    major: Int = 4,
    crosshair: Dp = 3.dp,
): Modifier = drawBehind {
    val c = cell.toPx()
    if (c <= 0f) return@drawBehind
    val cross = crosshair.toPx()
    val thin = color.copy(alpha = color.alpha * 0.65f)
    var x = 0f
    while (x <= size.width) { drawLine(thin, Offset(x, 0f), Offset(x, size.height), 1f); x += c }
    var y = 0f
    while (y <= size.height) { drawLine(thin, Offset(0f, y), Offset(size.width, y), 1f); y += c }
    var gy = 0
    y = 0f
    while (y <= size.height) {
        var gx = 0
        x = 0f
        while (x <= size.width) {
            if (gx % major == 0 && gy % major == 0) {
                drawLine(color, Offset(x - cross, y), Offset(x + cross, y), 1.5f)
                drawLine(color, Offset(x, y - cross), Offset(x, y + cross), 1.5f)
            }
            x += c; gx++
        }
        y += c; gy++
    }
}

/**
 * Large thin **construction circles / arcs** + small square registration markers over the background
 * (ref: dark "Unbounded" blueprint poster). Layer this ON TOP of [blueprintGrid] for the dark theme —
 * concentric circles up top, big arcs sweeping in from the side edges, a lower arc. Static, cheap.
 */
fun Modifier.blueprintCircles(
    color: Color,
    stroke: Dp = 1.dp,
): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    val st = Stroke(stroke.toPx())
    drawCircle(color, radius = w * 0.60f, center = Offset(w * 0.5f, h * 0.10f), style = st)
    drawCircle(color, radius = w * 0.44f, center = Offset(w * 0.5f, h * 0.44f), style = st)
    drawCircle(color, radius = w * 0.30f, center = Offset(w * 0.5f, h * 0.44f), style = st)
    drawCircle(color, radius = w * 0.58f, center = Offset(-w * 0.12f, h * 0.52f), style = st)
    drawCircle(color, radius = w * 0.58f, center = Offset(w * 1.12f, h * 0.52f), style = st)
    drawCircle(color, radius = w * 0.52f, center = Offset(w * 0.5f, h * 0.86f), style = st)
    val sq = 5.dp.toPx()
    val mk = color.copy(alpha = (color.alpha * 2.2f).coerceAtMost(0.9f))
    fun mark(fx: Float, fy: Float) =
        drawRect(mk, topLeft = Offset(w * fx - sq / 2f, h * fy - sq / 2f), size = Size(sq, sq))
    mark(0.30f, 0.30f); mark(0.70f, 0.30f); mark(0.30f, 0.62f); mark(0.70f, 0.62f)
}
