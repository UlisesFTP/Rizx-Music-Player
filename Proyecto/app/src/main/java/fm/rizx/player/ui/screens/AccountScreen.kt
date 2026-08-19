package fm.rizx.player.ui.screens

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import fm.rizx.player.BuildConfig
import fm.rizx.player.R
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.ui.account.AccountViewModel
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

@Composable
fun AccountScreen(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val c = RizxTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by vm.accountState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var googleRequesting by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = { Text(stringResource(R.string.account_delete_body)) },
            confirmButton = {
                Button(onClick = { confirmDelete = false; vm.deleteCloudAccount() }) {
                    Text(stringResource(R.string.account_delete_confirm))
                }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RizxIconButton(
                icon = RizxIcons.Back,
                contentDescription = stringResource(R.string.detail_back),
                onClick = onBack,
                size = 48.dp,
                iconSize = 24.dp,
            )
            Text(
                stringResource(R.string.account_title),
                style = sg(24, FontWeight.Bold, -0.02f),
                color = c.text,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        AccountStatusCard(
            title = accountTitle(account),
            caption = accountCaption(account),
            optional = account !is AccountState.SignedIn,
        )

        if (!vm.configured) {
            AccountFeedback(
                text = stringResource(R.string.account_not_configured),
                isError = false,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        if (account !is AccountState.SignedIn) {
            Text(
                stringResource(R.string.account_signin_heading),
                style = sg(22, FontWeight.Bold, -0.02f),
                color = c.text,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                stringResource(R.string.account_signin_caption),
                style = mr(13, FontWeight.Medium),
                color = c.text2,
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )
            GoogleSignInButton(
                onClick = {
                    if (googleRequesting) return@GoogleSignInButton
                    googleRequesting = true
                    scope.launch {
                        runCatching {
                            val rawNonce = randomNonce()
                            val option = GetGoogleIdOption.Builder()
                                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                .setFilterByAuthorizedAccounts(false)
                                .setAutoSelectEnabled(false)
                                .setNonce(sha256(rawNonce))
                                .build()
                            val result = CredentialManager.create(context).getCredential(
                                context,
                                GetCredentialRequest.Builder().addCredentialOption(option).build(),
                            )
                            val credential = result.credential as? CustomCredential
                                ?: error(context.getString(R.string.account_google_invalid))
                            require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                            GoogleIdTokenCredential.createFrom(credential.data).idToken to rawNonce
                        }.onSuccess { (token, nonce) -> vm.signInGoogle(token, nonce) }
                            .onFailure { vm.reportError(it.message ?: context.getString(R.string.account_google_failed)) }
                        googleRequesting = false
                    }
                },
                enabled = !ui.isWorking && !googleRequesting && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
                loading = googleRequesting,
            )

        } else {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.account_sync_scope), style = mr(13, FontWeight.Medium), color = c.text)
            Text(stringResource(R.string.account_sync_excludes), style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(
                onClick = vm::syncNow,
                enabled = !ui.isWorking,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.account_sync_now, pendingCount)) }
            OutlinedButton(
                onClick = vm::signOut,
                enabled = !ui.isWorking,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.account_sign_out)) }
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !ui.isWorking,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.account_delete)) }
        }

        if (ui.isWorking) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).background(c.elev).border(1.dp, c.line2).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.redAccent, strokeWidth = 2.dp)
                Text(stringResource(R.string.account_working), style = mr(12, FontWeight.SemiBold), color = c.text2)
            }
        }
        ui.message?.let {
            AccountFeedback(text = it, isError = false, modifier = Modifier.padding(top = 14.dp))
        }
        ui.error?.let {
            AccountFeedback(text = it, isError = true, modifier = Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(104.dp))
    }
}

@Composable
private fun AccountStatusCard(title: String, caption: String, optional: Boolean) {
    val c = RizxTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.elev).border(1.5.dp, c.hardLine).padding(18.dp),
    ) {
        if (optional) {
            Text(
                stringResource(R.string.account_optional_badge),
                style = code(10, FontWeight.Bold),
                color = c.redAccent,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(title, style = sg(20, FontWeight.Bold), color = c.text)
        Text(caption, style = mr(13, FontWeight.Medium), color = c.text2, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean, loading: Boolean) {
    val buttonBackground = Color(0xFF131314)
    val buttonContent = Color(0xFFE3E3E3)
    val buttonBorder = Color(0xFF8E918F)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, buttonBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonBackground,
            contentColor = buttonContent,
            disabledContainerColor = buttonBackground.copy(alpha = 0.62f),
            disabledContentColor = buttonContent.copy(alpha = 0.62f),
        ),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (loading) R.string.account_google_loading else R.string.account_google),
                style = mr(14, FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = buttonContent, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun AccountFeedback(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.elev).border(1.dp, if (isError) c.redAccent else c.line2),
    ) {
        if (isError) Box(Modifier.width(4.dp).heightIn(min = 48.dp).background(c.redAccent))
        Text(
            text,
            style = mr(12, FontWeight.SemiBold),
            color = if (isError) c.redAccent else c.text,
            modifier = Modifier.weight(1f).padding(14.dp),
        )
    }
}

@Composable
private fun accountTitle(state: AccountState): String = when (state) {
    AccountState.Disabled -> stringResource(R.string.account_local)
    AccountState.LocalOnly -> stringResource(R.string.account_local)
    is AccountState.Guest -> stringResource(R.string.account_guest)
    is AccountState.SignedIn -> state.profile.email ?: stringResource(R.string.account_connected)
}

@Composable
private fun accountCaption(state: AccountState): String = when (state) {
    AccountState.Disabled -> stringResource(R.string.account_local_caption)
    AccountState.LocalOnly -> stringResource(R.string.account_local_caption)
    is AccountState.Guest -> stringResource(R.string.account_guest_caption)
    is AccountState.SignedIn -> stringResource(R.string.account_connected_caption)
}

private fun randomNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes)
    .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
