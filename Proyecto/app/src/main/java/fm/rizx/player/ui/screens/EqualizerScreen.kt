package fm.rizx.player.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.EqBand
import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqualizerState
import fm.rizx.player.ui.components.Editorial
import fm.rizx.player.ui.components.RizxToggle
import fm.rizx.player.ui.components.SignalEyebrow
import fm.rizx.player.ui.components.SurfaceTitle
import fm.rizx.player.ui.components.bottomRule
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.topRule
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.settings.EqualizerViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.dotGrid
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.pagePadding
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.EqRadarGeometry
import fm.rizx.player.ui.util.rememberRizxHaptics
import kotlin.math.roundToInt

/** The ISO octave anchors the design draws when no device band count is known yet. */
private val ANCHORS_HZ = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

/** The range to draw before a session reports its own: ±15 dB, what most Android equalizers offer. */
private const val FALLBACK_RANGE_MB = 1500

/** Sliders move in half-decibel steps, as the design's range inputs do. */
private const val STEP_MB = 50

/**
 * The equalizer as the feed design's audio lab: a sticky header and preset strip, then the **profile
 * readout** — a radar whose vertices are the bands — and the **manual control**: one vertical slider per
 * band with its dB readout, a power switch and a footnote. While the automatic equalizer owns the curve
 * the workspace is dimmed and read-only, and the description says so; the presets are disabled too.
 *
 * Without a session there is still a screen: the ten ISO anchors drawn flat, and the readout saying to
 * play something. A blank page with one sentence on it would not look like the rest of the app.
 */
@Composable
fun EqualizerScreen(onBack: () -> Unit, vm: EqualizerViewModel = hiltViewModel()) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val margin = pagePadding()

    val bands = state.bands.ifEmpty { ANCHORS_HZ.mapIndexed { index, hz -> EqBand(index, hz, 0) } }
    val minMb = if (state.available) state.minLevelMillibel else -FALLBACK_RANGE_MB
    val maxMb = if (state.available) state.maxLevelMillibel else FALLBACK_RANGE_MB
    // Read-only while auto owns the bands: the sliders still move — they are the clearest possible
    // readout of what each song got — but they are not something to drag, because the next track would
    // overwrite it.
    val editable = state.available && state.enabled && !state.auto
    val powerOn = state.enabled || state.auto

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        EqHeader(onBack, margin)
        PresetStrip(
            active = state.preset,
            enabled = state.available && !state.auto,
            margin = margin,
            onPreset = vm::applyPreset,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProfileReadout(
                bands = bands,
                minMb = minMb,
                maxMb = maxMb,
                dimmed = !editable,
                description = when {
                    !state.available -> stringResource(R.string.eq_unavailable_message)
                    state.auto -> stringResource(R.string.eq_auto_hint)
                    else -> stringResource(R.string.eq_description)
                },
                margin = margin,
            )
            ManualControl(
                state = state,
                bands = bands,
                minMb = minMb,
                maxMb = maxMb,
                editable = editable,
                powerOn = powerOn,
                onPower = { if (state.available && !state.auto) vm.setEnabled(!state.enabled) },
                onBand = vm::setBand,
                margin = margin,
            )
            // The mini-player floats above this screen; RizxApp measures the complete floating chrome.
            Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
        }
    }
}

// ---- header + presets --------------------------------------------------------------------------

/** Eyebrow and title on the left, the framed close square on the right, over a 2dp rule. */
@Composable
private fun EqHeader(onBack: () -> Unit, margin: Dp) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.elev)
            .bottomRule(c.hardLine, Editorial.Frame)
            .padding(start = margin, end = margin, top = 22.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.weight(1f)) {
            SignalEyebrow(stringResource(R.string.eq_eyebrow))
            Text(
                stringResource(R.string.eq_title),
                style = sg(30, FontWeight.Medium, -0.055f, lineHeight = 30),
                color = c.text,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .border(Editorial.Frame, c.hardLine, RectangleShape)
                .clickableScale(scale = 0.9f, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RizxIcons.Close, stringResource(R.string.eq_cd_back), tint = c.text, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The preset chips in a strip that scrolls sideways: `PRESETS`, one chip per preset — the one whose
 * curve the bands currently equal is an ink block — and a red-edged `RESET` at the end.
 */
@Composable
private fun PresetStrip(active: EqPreset?, enabled: Boolean, margin: Dp, onPreset: (EqPreset) -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.elev.copy(alpha = 0.95f))
            .bottomRule(Editorial.rule)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = margin, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.eq_section_presets),
            style = code(10, FontWeight.Medium, 0.11f),
            color = c.muted,
            modifier = Modifier.padding(end = 4.dp),
        )
        EqPreset.entries.forEach { preset ->
            PresetChip(
                label = stringResource(preset.labelResource()),
                active = preset == active,
                enabled = enabled,
                onClick = { onPreset(preset) },
            )
        }
        PresetChip(
            label = stringResource(R.string.eq_reset),
            active = false,
            enabled = enabled,
            edge = c.redAccent,
            ink = c.redAccent,
            onClick = { onPreset(EqPreset.FLAT) },
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    edge: Color = Editorial.rule,
    ink: Color = RizxTheme.colors.text,
) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RectangleShape)
            .background(if (active) c.accent else Color.Transparent)
            .border(1.dp, if (active) c.accent else edge, RectangleShape)
            .clickableScale(scale = 0.95f, enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = code(10, FontWeight.Medium, 0.04f), color = if (active) c.onFill else ink, maxLines = 1)
    }
}

@StringRes
private fun EqPreset.labelResource(): Int = when (this) {
    EqPreset.FLAT -> R.string.eq_preset_flat
    EqPreset.BASS -> R.string.eq_preset_bass
    EqPreset.TREBLE -> R.string.eq_preset_treble
    EqPreset.VOCAL -> R.string.eq_preset_vocal
    EqPreset.LOUDNESS -> R.string.eq_preset_loudness
    EqPreset.POP -> R.string.eq_preset_pop
    EqPreset.ROCK -> R.string.eq_preset_rock
    EqPreset.HIP_HOP -> R.string.eq_preset_hip_hop
    EqPreset.ELECTRONIC -> R.string.eq_preset_electronic
    EqPreset.LATIN -> R.string.eq_preset_latin
    EqPreset.RNB -> R.string.eq_preset_rnb
    EqPreset.JAZZ -> R.string.eq_preset_jazz
    EqPreset.CLASSICAL -> R.string.eq_preset_classical
    EqPreset.ACOUSTIC -> R.string.eq_preset_acoustic
    EqPreset.METAL -> R.string.eq_preset_metal
    EqPreset.PODCAST -> R.string.eq_preset_podcast
    EqPreset.NIGHT -> R.string.eq_preset_night
}

// ---- profile readout ----------------------------------------------------------------------------

/** The radar on its dotted paper panel: heading with the range, the chart, and the description under it. */
@Composable
private fun ProfileReadout(
    bands: List<EqBand>,
    minMb: Int,
    maxMb: Int,
    dimmed: Boolean,
    description: String,
    margin: Dp,
) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .dotGrid(c.text.copy(alpha = 0.12f), spacing = 18.dp, dotRadius = 1.dp)
            .bottomRule(c.hardLine, Editorial.Frame)
            .padding(start = margin, end = margin, top = 24.dp, bottom = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.eq_readout_eyebrow), style = code(11, FontWeight.Medium, 0.13f), color = c.text)
                SurfaceTitle(stringResource(R.string.eq_readout_title), Modifier.padding(top = 5.dp), size = 28)
            }
            Text("−${-minMb / 100} / +${maxMb / 100} dB", style = mr(10, FontWeight.Medium), color = c.muted)
        }
        Radar(
            bands, minMb, maxMb,
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 12.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(EqRadarGeometry.VIEW_WIDTH / EqRadarGeometry.VIEW_HEIGHT)
                .alpha(if (dimmed) 0.42f else 1f),
        )
        Text(
            description,
            style = mr(12, FontWeight.Medium, lineHeight = 17),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 340.dp).padding(top = 8.dp),
        )
    }
}

/**
 * The design's radar, drawn from [EqRadarGeometry] in its 360 × 340 box and scaled to the space it gets:
 * three rings and the spokes, the red shape with a ringed point per band, the ink centre, and a frequency
 * label just outside each vertex. Each vertex animates over 180 ms, as the web's `transition` does.
 */
@Composable
private fun Radar(bands: List<EqBand>, minMb: Int, maxMb: Int, modifier: Modifier) {
    val c = RizxTheme.colors
    val measurer = rememberTextMeasurer()
    val count = bands.size
    // One animated value per band; the count is stable for the life of a session.
    val levels = bands.map { band ->
        animateFloatAsState(band.levelMillibel.toFloat(), animationSpec = tween(180), label = "band${band.index}")
    }
    val labelStyle = code(9, FontWeight.Medium, 0.07f).copy(color = c.text)
    val labels = bands.map { EqRadarGeometry.frequencyLabel(it.centerFreqHz) }
    Canvas(modifier) {
        val scale = minOf(size.width / EqRadarGeometry.VIEW_WIDTH, size.height / EqRadarGeometry.VIEW_HEIGHT)
        val dx = (size.width - EqRadarGeometry.VIEW_WIDTH * scale) / 2f
        val dy = (size.height - EqRadarGeometry.VIEW_HEIGHT * scale) / 2f
        fun at(index: Int, radius: Float): Offset {
            val (x, y) = EqRadarGeometry.polar(index, count, radius)
            return Offset(dx + x * scale, dy + y * scale)
        }
        fun ring(radius: Float): Path = Path().apply {
            for (i in 0 until count) {
                val p = at(i, radius)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        if (count == 0) return@Canvas

        // Grid: the outer ring in full ink, the inner two faint, the spokes faint.
        EqRadarGeometry.RING_RADII.forEachIndexed { index, radius ->
            drawPath(
                ring(radius),
                color = if (index == 0) c.text else c.text.copy(alpha = 0.22f),
                style = Stroke(width = (if (index == 0) 1.5f else 1f) * scale),
            )
        }
        val outer = EqRadarGeometry.RING_RADII[0]
        val spokes = Path().apply {
            if (count % 2 == 0) {
                for (i in 0 until count / 2) {
                    val a = at(i, outer)
                    val b = at(i + count / 2, outer)
                    moveTo(a.x, a.y); lineTo(b.x, b.y)
                }
            } else {
                val centre = Offset(dx + EqRadarGeometry.CENTER_X * scale, dy + EqRadarGeometry.CENTER_Y * scale)
                for (i in 0 until count) {
                    val p = at(i, outer)
                    moveTo(centre.x, centre.y); lineTo(p.x, p.y)
                }
            }
        }
        drawPath(spokes, color = c.text.copy(alpha = 0.22f), style = Stroke(width = 1f * scale))

        // The shape.
        val vertices = List(count) { i ->
            at(i, EqRadarGeometry.shapeRadius(levels[i].value / 100f, minMb / 100f, maxMb / 100f))
        }
        val shape = Path().apply {
            vertices.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(shape, color = c.redAccent.copy(alpha = 0.24f))
        drawPath(shape, color = c.redAccent, style = Stroke(width = 3f * scale))
        vertices.forEach { p ->
            drawCircle(c.elev, radius = 6f * scale, center = p)
            drawCircle(c.redAccent, radius = 6f * scale, center = p, style = Stroke(width = 3f * scale))
        }
        drawCircle(c.text, radius = 4f * scale, center = Offset(dx + EqRadarGeometry.CENTER_X * scale, dy + EqRadarGeometry.CENTER_Y * scale))

        // Labels, centred on a point just outside the outer ring.
        labels.forEachIndexed { i, label ->
            val p = at(i, EqRadarGeometry.LABEL_RADIUS)
            val layout = measurer.measure(label, labelStyle)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

// ---- manual control -----------------------------------------------------------------------------

/** Heading with the power switch, the band sliders between two rules, and the footnote. */
@Composable
private fun ManualControl(
    state: EqualizerState,
    bands: List<EqBand>,
    minMb: Int,
    maxMb: Int,
    editable: Boolean,
    powerOn: Boolean,
    onPower: () -> Unit,
    onBand: (Int, Int) -> Unit,
    margin: Dp,
) {
    val c = RizxTheme.colors
    val switchEnabled = state.available && !state.auto
    Column(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 24.dp, bottom = 34.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.eq_control_eyebrow), style = code(11, FontWeight.Medium, 0.13f), color = c.text)
                SurfaceTitle(stringResource(R.string.eq_bands_title), Modifier.padding(top = 5.dp), size = 28)
            }
            // With the automatic equalizer on there is nothing here to switch: it holds the effect open by
            // definition, so the switch shows the truth (on) and does not answer.
            Box(Modifier.alpha(if (switchEnabled) 1f else 0.45f)) {
                RizxToggle(checked = powerOn, onToggle = onPower)
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 22.dp)
                .topRule(Editorial.rule)
                .bottomRule(Editorial.rule)
                .alpha(if (editable) 1f else 0.42f),
        ) {
            // One column per band; when they do not fit, the row scrolls sideways rather than squeezing.
            val gap = 8.dp
            val fit = (maxWidth - gap * (bands.size - 1).coerceAtLeast(0)) / bands.size.coerceAtLeast(1)
            val column = if (fit < MIN_BAND_WIDTH) MIN_BAND_WIDTH else fit
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                bands.forEach { band ->
                    BandColumn(
                        band = band,
                        minMb = minMb,
                        maxMb = maxMb,
                        enabled = editable,
                        width = column,
                        onChange = { onBand(band.index, it) },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LIVE_GREEN))
                Text(stringResource(R.string.eq_footnote).uppercase(), style = code(9, FontWeight.Medium, 0.06f), color = c.muted, maxLines = 2)
            }
            // The genre the automatic equalizer decided on is the user's only way to see *why* a song is
            // being shaped the way it is — and so their cue to switch the feature off if it guessed wrong.
            val readout = when {
                state.auto -> state.autoLabel?.let { stringResource(R.string.eq_auto_badge_genre, it.uppercase()) }
                    ?: stringResource(R.string.eq_auto_badge)
                else -> stringResource(R.string.eq_bands_readout, bands.size, maxMb / 100)
            }
            Text(readout, style = code(9, FontWeight.SemiBold, 0.06f), color = if (state.auto) c.redAccent else c.text, textAlign = TextAlign.End, maxLines = 2)
        }
    }
}

/** `+2.4 dB` over the slider over `250 Hz` over `LOW`. */
@Composable
private fun BandColumn(
    band: EqBand,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    width: Dp,
    onChange: (Int) -> Unit,
) {
    val c = RizxTheme.colors
    val frequency = EqRadarGeometry.frequencyLabel(band.centerFreqHz)
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            EqRadarGeometry.formatDecibels(band.levelMillibel),
            style = code(10, FontWeight.Medium, 0.02f),
            color = c.redAccent,
            maxLines = 1,
            modifier = Modifier.height(22.dp),
        )
        BandSlider(
            value = band.levelMillibel,
            min = minMb,
            max = maxMb,
            enabled = enabled,
            onChange = onChange,
            description = stringResource(R.string.eq_band_desc, frequency),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(SLIDER_HEIGHT),
        )
        Text(frequency, style = code(10, FontWeight.Medium, 0.02f), color = c.text, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(bandShortName(band.centerFreqHz)), style = code(8, FontWeight.Medium, 0.06f), color = c.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The design's vocabulary for where a band sits. */
@StringRes
private fun bandShortName(hz: Int): Int = when {
    hz < 50 -> R.string.eq_band_sub
    hz < 200 -> R.string.eq_band_low
    hz < 2_000 -> R.string.eq_band_mid
    hz < 6_000 -> R.string.eq_band_presence
    else -> R.string.eq_band_air
}

/**
 * A vertical slider drawn like the design's rotated range input: an 8dp track in a hairline, dark at
 * the top and paper at the bottom, and a 22dp red square thumb with an ink edge and a hard shadow.
 *
 * Drag and tap live on two separate pointer nodes: one node handling both eats the tap. Every
 * half-decibel step ticks, so the thumb feels like a detented fader rather than a glide.
 */
@Composable
private fun BandSlider(
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    val haptics = rememberRizxHaptics()
    val range = (max - min).coerceAtLeast(1)
    val fraction = ((value - min).toFloat() / range).coerceIn(0f, 1f)
    val last = remember { mutableIntStateOf(value) }

    fun set(y: Float, height: Int, density: Float) {
        val thumb = THUMB.value * density
        val top = thumb / 2f
        val bottom = height - thumb / 2f
        val f = 1f - ((y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val stepped = ((min + f * range) / STEP_MB).roundToInt() * STEP_MB
        val next = stepped.coerceIn(min, max)
        if (next != last.intValue) {
            last.intValue = next
            haptics.select()
            onChange(next)
        }
    }

    Box(
        modifier
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat())
                if (enabled) setProgress { target -> onChange(target.roundToInt().coerceIn(min, max)); true }
            }
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { position -> set(position.y, size.height, density) },
                ) { change, _ ->
                    change.consume()
                    set(change.position.y, size.height, density)
                }
            }
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                detectTapGestures { position -> set(position.y, size.height, density) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackWidth = TRACK.toPx()
            val thumb = THUMB.toPx()
            val top = thumb / 2f
            val bottom = size.height - thumb / 2f
            val cx = size.width / 2f
            val trackRect = Offset(cx - trackWidth / 2f, top)
            val trackSize = Size(trackWidth, (bottom - top).coerceAtLeast(0f))
            drawRect(
                brush = Brush.verticalGradient(listOf(c.accent, c.inset), startY = top, endY = bottom),
                topLeft = trackRect,
                size = trackSize,
            )
            drawRect(c.hardLine, topLeft = trackRect, size = trackSize, style = Stroke(1.dp.toPx()))

            val y = bottom - (bottom - top) * fraction
            val thumbTopLeft = Offset(cx - thumb / 2f, y - thumb / 2f)
            translate(left = 2.dp.toPx(), top = 2.dp.toPx()) {
                drawRect(c.shadowHard.copy(alpha = 0.22f), topLeft = thumbTopLeft, size = Size(thumb, thumb))
            }
            drawRect(c.redAccent, topLeft = thumbTopLeft, size = Size(thumb, thumb))
            drawRect(c.hardLine, topLeft = thumbTopLeft, size = Size(thumb, thumb), style = Stroke(2.dp.toPx()))
        }
    }
}

/** The design's `minmax(56px, 1fr)` column: narrower and the readout wraps. */
private val MIN_BAND_WIDTH = 56.dp

/** The rotated range input's length on a phone. */
private val SLIDER_HEIGHT = 190.dp
private val TRACK = 8.dp
private val THUMB = 22.dp

/** The footnote's "live" dot, the one colour the design does not take from the palette. */
private val LIVE_GREEN = Color(0xFF58AA4F)
