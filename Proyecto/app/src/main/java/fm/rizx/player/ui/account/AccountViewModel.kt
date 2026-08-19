package fm.rizx.player.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.sync.SyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val account: AccountRepository,
    private val sync: SyncCoordinator,
) : ViewModel() {
    data class UiState(
        val isWorking: Boolean = false,
        val message: String? = null,
        val error: String? = null,
    )

    val accountState: StateFlow<AccountState> = account.state
    val configured: Boolean get() = account.configured
    val pendingCount = sync.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // Google is the only sign-in the UI offers (the owner removed the email-OTP path on
    // 2026-08-19); the repository still speaks OTP in case it ever comes back.
    fun signInGoogle(idToken: String, nonce: String) = runAction {
        account.signInWithGoogle(idToken, nonce)
        // Signing in IS the request to have your library on this device — sync starts by itself.
        sync.syncNow()
        _ui.value = UiState(message = "Sesión iniciada. Sincronizando tu biblioteca…")
    }

    fun reportError(message: String) = _ui.update { it.copy(error = message, isWorking = false) }

    fun signOut() = runAction {
        sync.stop()
        account.signOut()
        _ui.value = UiState(message = "La biblioteca permanece en este dispositivo")
    }

    fun syncNow() = sync.syncNow()

    fun deleteCloudAccount() = runAction {
        sync.stop()
        account.deleteCloudAccount()
        _ui.value = UiState(message = "Cuenta y datos en la nube eliminados; tu biblioteca local permanece.")
    }

    private fun runAction(block: suspend () -> Unit) {
        if (_ui.value.isWorking) return
        viewModelScope.launch {
            _ui.update { it.copy(isWorking = true, error = null, message = null) }
            runCatching { block() }
                .onFailure { error -> _ui.update { it.copy(error = error.message ?: "No se pudo completar", isWorking = false) } }
                .onSuccess { _ui.update { it.copy(isWorking = false) } }
        }
    }
}
