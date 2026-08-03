package fm.rizx.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fm.rizx.player.R

/** The two answers to "where should this download end up?". */
private enum class SavePlace { RIZX_ONLY, ALSO_PHONE }

/**
 * The one-time question, asked on the first download.
 *
 * It exists because neither answer is obviously right: keeping the song app-private is what the app has
 * always done and costs nothing extra, while a copy in the phone's Music folder is the only way a file
 * manager or another player will ever see it — and it doubles what the song takes up. So the user
 * decides, once, and can change it in Settings afterwards.
 *
 * [onChoose] carries `true` for "also on the phone". Dismissing chooses nothing.
 */
@Composable
fun SaveDownloadsToPhoneDialog(onChoose: (Boolean) -> Unit, onDismiss: () -> Unit) {
    CaptionedOptionDialog(
        title = stringResource(R.string.save_to_phone_title),
        options = SavePlace.entries,
        // No tick: this is a question being put for the first time, not a setting being reviewed.
        current = null,
        label = {
            stringResource(
                when (it) {
                    SavePlace.RIZX_ONLY -> R.string.save_to_phone_only_rizx
                    SavePlace.ALSO_PHONE -> R.string.save_to_phone_also_phone
                },
            )
        },
        caption = {
            stringResource(
                when (it) {
                    SavePlace.RIZX_ONLY -> R.string.save_to_phone_only_rizx_caption
                    SavePlace.ALSO_PHONE -> R.string.save_to_phone_also_phone_caption
                },
            )
        },
        onSelect = { onChoose(it == SavePlace.ALSO_PHONE) },
        onDismiss = onDismiss,
    )
}
