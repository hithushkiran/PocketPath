package com.pocketpath.feature.auth

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
fun AuthRoot(viewModel: AuthViewModel) {
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
            // Dispose this navigation tree on logout or UID changes.
            key(current.user.uid) {
                PocketPathApp(
                    email = current.user.email,
                    authBusy = action.busy,
                    authError = action.messageRes
                        ?.takeIf { action.isError }
                        ?.let { stringResource(it) },
                    onSignOut = viewModel::signOut,
                )
            }
        }
    }
}
