package fm.rizx.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import fm.rizx.player.R

/**
 * Turns a snapshot into the RemoteViews of one widget. The layouts carry the dark preview; everything
 * that depends on the theme or the song is set here, per instance, because the launcher hands each
 * widget its own width and height.
 */
class WidgetRenderer(private val context: Context, private val bitmaps: WidgetBitmaps = WidgetBitmaps(context)) {

    enum class Kind { CARD, BAR }

    fun render(kind: Kind, snapshot: WidgetSnapshot, palette: WidgetPalette, art: Bitmap?, widthDp: Int, heightDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, if (kind == Kind.CARD) R.layout.widget_now_playing_card else R.layout.widget_now_playing_bar)
        views.setInt(R.id.widget_root, "setBackgroundResource", palette.cardRes)

        if (art != null) views.setImageViewBitmap(R.id.art, art) else views.setImageViewResource(R.id.art, R.drawable.widget_art_placeholder)

        val title = (snapshot.title ?: context.getString(R.string.widget_nothing_playing)).uppercase()
        val titleWidthDp = when (kind) {
            Kind.CARD -> widthDp - CARD_PADDING_DP * 2 - CARD_ART_DP - CARD_GAP_DP
            Kind.BAR -> widthDp - BAR_PADDING_DP * 2 - BAR_ART_DP - BAR_TEXT_MARGINS_DP - BAR_BUTTONS_DP
        }.coerceAtLeast(MIN_TITLE_DP)
        val titleSp = if (kind == Kind.CARD) CARD_TITLE_SP else BAR_TITLE_SP
        views.setImageViewBitmap(R.id.title_image, bitmaps.title(title, bitmaps.dp(titleWidthDp.toFloat()), titleSp, palette.text))

        val artist = snapshot.artist?.let { context.getString(R.string.widget_artist_prefix, it) }
            ?: context.getString(R.string.widget_open_rizx)
        views.setTextViewText(R.id.artist, artist)
        views.setTextColor(R.id.artist, palette.text2)

        if (kind == Kind.CARD) {
            val total = if (snapshot.durationMs > 0L) formatClock(snapshot.durationMs) else UNKNOWN_CLOCK
            views.setTextViewText(R.id.clock, "${formatClock(snapshot.positionMs)} / $total")
            views.setTextColor(R.id.clock, palette.text2)
            // The seek bar: red squares up to the playhead, a red knob on it, and a tap zone per segment.
            val dots = bitmaps.dots(bitmaps.dp((widthDp - CARD_PADDING_DP * 2).toFloat()), snapshot.progress, palette.red, palette.dotOff, knob = palette.red)
            if (dots != null) views.setImageViewBitmap(R.id.dots, dots)
            SEEK_ZONES.forEachIndexed { index, id ->
                views.setOnClickPendingIntent(id, if (snapshot.hasTrack) WidgetActions.seek(context, index) else null)
            }
            // A launcher that gives the card less room than it wants loses the quiet rows first.
            views.setViewVisibility(R.id.row_labels, if (heightDp >= LABELS_MIN_HEIGHT_DP) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.seek_area, if (heightDp >= DOTS_MIN_HEIGHT_DP) View.VISIBLE else View.GONE)
        }

        // Transport: outlined squares, the play block filled, a red marker on it while playing.
        for (id in intArrayOf(R.id.btn_prev, R.id.btn_next)) {
            views.setInt(id, "setBackgroundResource", palette.squareRes)
            views.setInt(id, "setColorFilter", palette.text)
        }
        views.setInt(R.id.btn_play, "setBackgroundResource", palette.fillRes)
        views.setImageViewResource(R.id.btn_play, if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setInt(R.id.btn_play, "setColorFilter", palette.onFill)
        views.setContentDescription(R.id.btn_play, context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play))
        views.setInt(R.id.play_marker, "setBackgroundColor", palette.red)
        views.setViewVisibility(R.id.play_marker, if (snapshot.isPlaying) View.VISIBLE else View.GONE)

        // Like: red block with the filled heart when liked, an outlined square otherwise.
        if (snapshot.liked) {
            views.setInt(R.id.btn_like, "setBackgroundResource", palette.likedRes)
            views.setImageViewResource(R.id.btn_like, R.drawable.ic_media_favorite_filled)
            views.setInt(R.id.btn_like, "setColorFilter", palette.onRed)
        } else {
            views.setInt(R.id.btn_like, "setBackgroundResource", palette.squareRes)
            views.setImageViewResource(R.id.btn_like, R.drawable.ic_media_favorite)
            views.setInt(R.id.btn_like, "setColorFilter", palette.text)
        }
        views.setContentDescription(R.id.btn_like, context.getString(if (snapshot.liked) R.string.widget_unlike else R.string.player_like))

        if (kind == Kind.CARD) {
            views.setInt(R.id.btn_mic, "setBackgroundResource", palette.pillRes)
            views.setInt(R.id.btn_mic, "setColorFilter", palette.onFill)
            views.setOnClickPendingIntent(R.id.btn_mic, WidgetActions.recognize(context))
        }

        // Without a song there is nothing to skip or like; play opens the app instead of a silent no-op.
        val enabled = if (snapshot.hasTrack) 1f else DISABLED_ALPHA
        for (id in intArrayOf(R.id.btn_prev, R.id.btn_next, R.id.btn_like)) views.setFloat(id, "setAlpha", enabled)
        val open = WidgetActions.open(context)
        views.setOnClickPendingIntent(R.id.btn_prev, WidgetActions.broadcast(context, WidgetActions.PREVIOUS))
        views.setOnClickPendingIntent(R.id.btn_next, WidgetActions.broadcast(context, WidgetActions.NEXT))
        views.setOnClickPendingIntent(R.id.btn_like, WidgetActions.broadcast(context, WidgetActions.LIKE))
        views.setOnClickPendingIntent(R.id.btn_play, if (snapshot.hasTrack || open == null) WidgetActions.broadcast(context, WidgetActions.PLAY_PAUSE) else open)
        if (open != null) {
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.art, open)
            views.setOnClickPendingIntent(R.id.title_image, open)
        }
        return views
    }

    /** The Audio ID card: the microphone, and whatever it last found. */
    fun renderAudioId(snapshot: AudioIdSnapshot, palette: WidgetPalette, art: Bitmap?, widthDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_audio_id)
        views.setInt(R.id.widget_root, "setBackgroundResource", palette.cardRes)

        if (art != null && snapshot.phase.showsResult) views.setImageViewBitmap(R.id.art, art)
        else views.setImageViewResource(R.id.art, R.drawable.widget_art_placeholder)

        val (title, line) = when (snapshot.phase) {
            AudioIdPhase.LISTENING -> R.string.widget_audio_listening to R.string.widget_audio_listening_hint
            AudioIdPhase.IDENTIFYING -> R.string.widget_audio_identifying to R.string.widget_audio_listening_hint
            AudioIdPhase.NO_MATCH -> R.string.widget_audio_no_match to R.string.widget_audio_retry_hint
            AudioIdPhase.FAILED -> R.string.widget_audio_failed to R.string.widget_audio_retry_hint
            AudioIdPhase.IDLE, AudioIdPhase.MATCHED -> null to null
        }
        val titleText = (title?.let { context.getString(it) } ?: snapshot.title ?: context.getString(R.string.widget_audio_idle_title)).uppercase()
        val titleWidthDp = (widthDp - AUDIO_PADDING_DP * 2 - AUDIO_ART_DP - AUDIO_GAP_DP).coerceAtLeast(MIN_TITLE_DP)
        views.setImageViewBitmap(R.id.title_image, bitmaps.title(titleText, bitmaps.dp(titleWidthDp.toFloat()), AUDIO_TITLE_SP, palette.text))

        // One line under the title: the phase's hint, or "artist · album" — a 2×2 card has no third row.
        val artistText = line?.let { context.getString(it) }
            ?: listOfNotNull(snapshot.artist, snapshot.album).takeIf { it.isNotEmpty() }?.joinToString(" · ")
            ?: context.getString(R.string.widget_audio_idle_hint_short)
        views.setTextViewText(R.id.artist, artistText)
        views.setTextColor(R.id.artist, palette.text2)

        views.setInt(R.id.btn_mic, "setBackgroundResource", palette.pillRes)
        views.setInt(R.id.btn_mic, "setColorFilter", palette.onFill)
        views.setOnClickPendingIntent(R.id.btn_mic, WidgetActions.recognize(context))

        val playable = snapshot.canPlay && snapshot.phase.showsResult
        views.setInt(R.id.btn_play, "setBackgroundResource", palette.fillRes)
        views.setInt(R.id.btn_play, "setColorFilter", palette.onFill)
        views.setFloat(R.id.btn_play, "setAlpha", if (playable) 1f else DISABLED_ALPHA)
        val open = WidgetActions.open(context)
        views.setOnClickPendingIntent(R.id.btn_play, if (playable || open == null) WidgetActions.broadcast(context, WidgetActions.PLAY_RECOGNIZED) else open)
        if (open != null) {
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.art, open)
            views.setOnClickPendingIntent(R.id.title_image, open)
        }
        return views
    }

    private val AudioIdPhase.showsResult: Boolean get() = this == AudioIdPhase.IDLE || this == AudioIdPhase.MATCHED

    private companion object {
        const val CARD_PADDING_DP = 14
        const val CARD_ART_DP = 44
        const val CARD_GAP_DP = 12
        const val CARD_TITLE_SP = 24f
        const val BAR_PADDING_DP = 10
        const val BAR_ART_DP = 40
        const val BAR_TEXT_MARGINS_DP = 18
        /** Three 36dp transport squares, the like square and their margins, as laid out in the bar. */
        const val BAR_BUTTONS_DP = 36 * 4 + 6 + 6 + 10
        const val BAR_TITLE_SP = 16f
        const val AUDIO_PADDING_DP = 12
        const val AUDIO_ART_DP = 44
        const val AUDIO_GAP_DP = 8
        const val AUDIO_TITLE_SP = 16f
        const val MIN_TITLE_DP = 48
        const val LABELS_MIN_HEIGHT_DP = 150
        const val DOTS_MIN_HEIGHT_DP = 128
        const val DISABLED_ALPHA = 0.35f
        const val UNKNOWN_CLOCK = "--:--"

        /** The tap zones laid over the seek bar, left to right (see the card layout). */
        val SEEK_ZONES = intArrayOf(
            R.id.seek_00, R.id.seek_01, R.id.seek_02, R.id.seek_03, R.id.seek_04, R.id.seek_05,
            R.id.seek_06, R.id.seek_07, R.id.seek_08, R.id.seek_09, R.id.seek_10, R.id.seek_11,
            R.id.seek_12, R.id.seek_13, R.id.seek_14, R.id.seek_15, R.id.seek_16, R.id.seek_17,
            R.id.seek_18, R.id.seek_19, R.id.seek_20, R.id.seek_21, R.id.seek_22, R.id.seek_23,
        )
    }
}
