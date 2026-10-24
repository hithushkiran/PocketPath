package com.pocketpath.feature.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.R
import com.pocketpath.domain.repository.AuthError
import com.pocketpath.domain.repository.AuthOperationResult
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthActionState(
    val busy: Boolean = false,
    @param:StringRes val messageRes: Int? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    val session = repository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AuthSession.Checking,
    )

    private val _actionState = MutableStateFlow(AuthActionState())
    val actionState = _actionState.asStateFlow()

    fun signIn(email: String, password: String) {
        if (_actionState.value.busy) return
        if (!requireEmail(email)) return

        if (password.isEmpty()) {
            showError(R.string.auth_error_password)
            return
        }

        runAction {
            repository.signIn(email.trim(), password)
        }
    }

    fun register(
        email: String,
        password: String,
        confirmation: String,
    ) {
        if (_actionState.value.busy) return
        if (!requireEmail(email)) return

        if (password.length !in 6..4096) {
            showError(R.string.auth_error_password_length)
            return
        }

        if (password != confirmation) {
            showError(R.string.auth_error_confirmation)
            return
        }

        runAction {
            repository.register(email.trim(), password)
        }
    }

    fun resetPassword(email: String) {
        if (_actionState.value.busy) return
        if (!requireEmail(email)) return

        runAction(successMessage = R.string.auth_reset_sent) {
            repository.resetPassword(email.trim())
        }
    }

    fun signOut() {
        runAction {
            repository.signOut()
        }
    }

    fun clearMessage() {
        if (!_actionState.value.busy) {
            _actionState.value = AuthActionState()
        }
    }

    private fun requireEmail(email: String): Boolean {
        if (email.trim().isEmpty()) {
            showError(R.string.auth_error_email)
            return false
        }

        // Firebase performs authoritative email-format validation.
        return true
    }

    private fun showError(@StringRes message: Int) {
        _actionState.value = AuthActionState(
            messageRes = message,
            isError = true,
        )
    }

    private fun runAction(
        @StringRes successMessage: Int? = null,
        operation: suspend () -> AuthOperationResult,
    ) {
        if (_actionState.value.busy) return

        // Set this before launching so repeated taps cannot start two requests.
        _actionState.value = AuthActionState(busy = true)

        viewModelScope.launch {
            try {
                _actionState.value = when (val result = operation()) {
                    AuthOperationResult.Success -> AuthActionState(
                        messageRes = successMessage,
                    )

                    is AuthOperationResult.Failure -> AuthActionState(
                        messageRes = result.error.messageResource(),
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(R.string.auth_error_generic)
            } finally {
                _actionState.update { it.copy(busy = false) }
            }
        }
    }
}

@StringRes
private fun AuthError.messageResource(): Int = when (this) {
    AuthError.INVALID_EMAIL -> R.string.auth_error_email
    AuthError.INVALID_CREDENTIALS -> R.string.auth_error_credentials
    AuthError.WEAK_PASSWORD -> R.string.auth_error_weak_password
    AuthError.REGISTRATION_REJECTED -> R.string.auth_error_account
    AuthError.NETWORK -> R.string.auth_error_network
    AuthError.TOO_MANY_REQUESTS -> R.string.auth_error_rate_limit
    AuthError.UNAVAILABLE -> R.string.auth_error_unavailable
    AuthError.UNKNOWN -> R.string.auth_error_generic
}
