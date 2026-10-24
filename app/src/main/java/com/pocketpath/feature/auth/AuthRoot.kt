package com.pocketpath.feature.auth

import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.feature.finance.FinanceViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketpath.R
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.navigation.PocketPathApp

@Composable
fun AuthRoot(
    viewModel: AuthViewModel,
    financeViewModel: FinanceViewModel,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val action by viewModel.actionState.collectAsStateWithLifecycle()

    when (val current = session) {
        AuthSession.Checking -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.auth_checking))
            }
        }

        AuthSession.SignedOut -> {
            AuthScreen(
                action = action,
                onSignIn = viewModel::signIn,
                onRegister = viewModel::register,
                onResetPassword = viewModel::resetPassword,
                onClearMessage = viewModel::clearMessage,
            )
        }

        is AuthSession.SignedIn -> {
            val financeState by financeViewModel.state.collectAsStateWithLifecycle()

            // Never render a previous user's state while session observers catch up.
            val visibleFinance = if (financeState.userId == current.user.uid) {
                financeState
            } else {
                FinanceUiState(userId = current.user.uid)
            }

            key(current.user.uid) {
                PocketPathApp(
                    email = current.user.email,
                    authBusy = action.busy,
                    authError = action.messageRes
                        ?.takeIf { action.isError }
                        ?.let { stringResource(it) },
                    onSignOut = viewModel::signOut,
                    financeState = visibleFinance,
                    onFinanceEvent = {
                        financeViewModel.onEvent(current.user.uid, it)
                    },
                )
            }
        }
    }
}
