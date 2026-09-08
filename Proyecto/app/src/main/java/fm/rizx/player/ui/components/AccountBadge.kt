package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fm.rizx.player.R
import fm.rizx.player.domain.account.AccountProfile
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * The account, as a square face: the Google profile photo when the session has one, the initial of
 * the name/email when it doesn't (email-OTP has no photo), and the person glyph when nobody is
 * signed in. Used in the Home header next to the heart and inside [AccountDialog]; both sit on the
 * same visual as [RizxIconButton] (square, elev background, hairline border).
 */
@Composable
fun AccountAvatar(
    state: AccountState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    /**
     * Edge to edge, without the paper square around the photo: for a host that already frames it,
     * like the ink account card in Settings, where the inset paper read as a stray tile.
     */
    bare: Boolean = false,
) {
    val c = RizxTheme.colors
    val profile = state.profileOrNull()
    val cd = stringResource(R.string.home_account_cd)
    Box(
        modifier
            .size(size)
            .clip(RectangleShape)
            .then(if (bare) Modifier else Modifier.background(c.elev).border(1.dp, c.line, RectangleShape))
            .then(if (onClick != null) Modifier.clickableScale(onClick = onClick) else Modifier)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        val avatar = profile?.avatarUrl?.takeIf { state is AccountState.SignedIn }
        val initial = profile?.takeIf { state is AccountState.SignedIn }
            ?.let { it.displayName ?: it.email }
            ?.firstOrNull(Char::isLetterOrDigit)?.uppercase()
        when {
            // Inset, not edge-to-edge: the photo floats inside the square with the paper showing
            // around it, so the button reads as a button and not as a stray image in the header.
            avatar != null -> coil.compose.AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (bare) Modifier else Modifier.padding(size / 8)),
            )
            initial != null -> Text(initial, style = sg(size.value.toInt() * 4 / 10, FontWeight.Bold), color = c.text)
            else -> Icon(RizxIcons.Person, null, tint = c.text, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * Tapping the avatar answers "who am I here, and what can I do about it": the profile (photo, name,
 * email) plus one action per situation — sign out, switch to another account (sign out first: the
 * Google chooser on the Account screen then offers every device account), or sign in when there is
 * no session yet. Kept to actions only; the full flows (OTP, delete, sync detail) live on the
 * Account screen this dialog links into.
 */
@Composable
fun AccountDialog(
    state: AccountState,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignIn: () -> Unit,
) {
    val c = RizxTheme.colors
    val profile = state.profileOrNull()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.elev)
                .border(1.5.dp, c.hardLine)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccountAvatar(state, size = 56.dp, iconSize = 26.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            is AccountState.SignedIn -> profile?.displayName ?: profile?.email
                                ?: stringResource(R.string.account_connected)
                            is AccountState.Guest -> stringResource(R.string.account_guest)
                            else -> stringResource(R.string.account_local)
                        },
                        style = sg(18, FontWeight.Bold, -0.01f),
                        color = c.text,
                    )
                    val secondary = (state as? AccountState.SignedIn)?.profile?.email
                        ?.takeIf { it != profile?.displayName && profile?.displayName != null }
                    if (secondary != null) {
                        Text(secondary, style = code(11, FontWeight.Bold), color = c.text2, modifier = Modifier.padding(top = 3.dp))
                    }
                    Text(
                        when (state) {
                            is AccountState.SignedIn -> stringResource(R.string.account_connected_caption)
                            is AccountState.Guest -> stringResource(R.string.account_guest_caption)
                            else -> stringResource(R.string.account_local_caption)
                        },
                        style = mr(12, FontWeight.Medium),
                        color = c.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            if (state is AccountState.SignedIn) {
                DialogAction(stringResource(R.string.account_sign_out)) { onSignOut(); onDismiss() }
                Spacer(Modifier.height(8.dp))
                DialogAction(stringResource(R.string.account_switch_account)) { onDismiss(); onSwitchAccount() }
            } else {
                DialogAction(stringResource(R.string.account_sign_in)) { onDismiss(); onSignIn() }
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(c.bg)
            .border(1.dp, c.hardLine)
            .clickableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = mr(13, FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

private fun AccountState.profileOrNull(): AccountProfile? = when (this) {
    is AccountState.SignedIn -> profile
    is AccountState.Guest -> profile
    else -> null
}
