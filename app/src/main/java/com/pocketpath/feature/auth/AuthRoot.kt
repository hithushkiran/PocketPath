package com.pocketpath.feature.auth

import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.feature.finance.FinanceViewModel
import com.pocketpath.feature.income.ManualIncomeUiState
import com.pocketpath.feature.income.ManualIncomeViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    incomeViewModel: ManualIncomeViewModel,
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
            val incomeState by incomeViewModel.state.collectAsStateWithLifecycle()

            // Never render a previous user's state while session observers catch up.
            val visibleFinance = if (financeState.userId == current.user.uid) {
                financeState
            } else {
                FinanceUiState(userId = current.user.uid)
            }
            val visibleIncome = if (incomeState.userId == current.user.uid) {
                incomeState
            } else {
                ManualIncomeUiState(userId = current.user.uid)
            }

            key(current.user.uid) {
                var confirmSignOut by remember { mutableStateOf(false) }

                PocketPathApp(
                    email = current.user.email,
                    authBusy = action.busy,
                    authError = action.messageRes
                        ?.takeIf { action.isError }
                        ?.let { stringResource(it) }
                        ?: visibleFinance.localError,
                    onSignOut = {
                        if (visibleFinance.hasUnresolvedLocalWork || visibleIncome.hasUnresolvedIncomeWork) {
                            confirmSignOut = true
                        } else {
                            financeViewModel.flushAndSignOut(
                                current.user.uid,
                                viewModel::signOut,
                            )
                        }
                    },
                    financeState = visibleFinance,
                    onFinanceEvent = {
                        financeViewModel.onEvent(current.user.uid, it)
                    },
                    incomeState = visibleIncome,
                    onIncomeEvent = { incomeViewModel.onEvent(current.user.uid, it) },
                )

                if (confirmSignOut) {
                    AlertDialog(
                        onDismissRequest = { confirmSignOut = false },
                        title = { Text("Sign out with local work?") },
                        text = {
                            Text(buildString {
                                if (visibleFinance.hasUnresolvedLocalWork) {
                                    append(
                                        "Drafts and unresolved saves will remain on this device " +
                                            "for this account. You may need to sign back in to " +
                                            "finish synchronization.",
                                    )
                                }
                                if (visibleIncome.hasUnresolvedIncomeWork) {
                                    if (isNotEmpty()) append("\n\n")
                                    append(
                                        "An uncertain income retry exists only in memory. " +
                                            "Signing out will discard its retry information, and " +
                                            "the income transaction may already have been recorded.",
                                    )
                                }
                            })
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmSignOut = false
                                    financeViewModel.flushAndSignOut(
                                        current.user.uid,
                                        viewModel::signOut,
                                    )
                                },
                            ) {
                                Text("Sign out")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSignOut = false }) {
                                Text("Stay signed in")
                            }
                        },
                    )
                }
            }
        }
    }
}
