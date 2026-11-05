package com.pocketpath.feature.auth

import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.feature.finance.FinanceViewModel
import com.pocketpath.feature.goals.GoalsUiState
import com.pocketpath.feature.goals.GoalsViewModel
import com.pocketpath.feature.income.ManualIncomeUiState
import com.pocketpath.feature.income.ManualIncomeViewModel
import com.pocketpath.feature.income.ExpectedPaymentsUiState
import com.pocketpath.feature.income.ExpectedPaymentsViewModel
import com.pocketpath.feature.income.ExpectedPaymentCreationUiState
import com.pocketpath.feature.income.ExpectedPaymentCreationViewModel
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
    goalsViewModel: GoalsViewModel,
    incomeViewModel: ManualIncomeViewModel,
    expectedPaymentsViewModel: ExpectedPaymentsViewModel,
    expectedPaymentCreationViewModel: ExpectedPaymentCreationViewModel,
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
            val goalsState by goalsViewModel.state.collectAsStateWithLifecycle()
            val visibleGoals = goalsState.takeIf { it.userId == current.user.uid } ?: GoalsUiState()
            val incomeState by incomeViewModel.state.collectAsStateWithLifecycle()
            val expectedPaymentsState by expectedPaymentsViewModel.state.collectAsStateWithLifecycle()
            val expectedPaymentCreationState by expectedPaymentCreationViewModel.state.collectAsStateWithLifecycle()

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
            val visibleExpectedPayments = if (expectedPaymentsState.userId == current.user.uid) expectedPaymentsState else ExpectedPaymentsUiState(userId = current.user.uid)
            val visibleExpectedPaymentCreation = if (expectedPaymentCreationState.userId == current.user.uid) expectedPaymentCreationState else ExpectedPaymentCreationUiState(userId = current.user.uid, loading = false)

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
                        if (visibleFinance.hasUnresolvedLocalWork || visibleFinance.monthlyBills.hasUnresolvedSave ||
                            visibleGoals.hasUnresolvedWrite ||
                            visibleIncome.hasUnresolvedIncomeWork ||
                            visibleIncome.hasUnresolvedSourceWork ||
                            visibleExpectedPayments.hasUnresolvedCancellationRetry || visibleExpectedPayments.receiptRecovery.hasWork
                        ) {
                            confirmSignOut = true
                        } else {
                            expectedPaymentsViewModel.flushReceiptAndContinue(current.user.uid) {
                                financeViewModel.flushAndSignOut(current.user.uid, viewModel::signOut)
                            }
                        }
                    },
                    financeState = visibleFinance,
                    goalsState = visibleGoals,
                    onGoalsEvent = { goalsViewModel.onEvent(current.user.uid, it) },
                    onFinanceEvent = {
                        financeViewModel.onEvent(current.user.uid, it)
                    },
                    incomeState = visibleIncome,
                    onIncomeEvent = { incomeViewModel.onEvent(current.user.uid, it) },
                    expectedPaymentsState = visibleExpectedPayments,
                    onExpectedPaymentCancel = {
                        expectedPaymentsViewModel.cancel(current.user.uid, it)
                    },
                    onExpectedPaymentReceiptAction = {
                        expectedPaymentsViewModel.onReceiptAction(current.user.uid, it)
                    },
                    onExpectedPaymentReceivingAccountSelected = { accountId ->
                        expectedPaymentsViewModel.selectReceivingAccount(current.user.uid, accountId)
                    },
                    expectedPaymentCreationState = visibleExpectedPaymentCreation,
                    onExpectedPaymentCreationEvent = { expectedPaymentCreationViewModel.onEvent(current.user.uid, it) },
                )

                visibleExpectedPayments.receiptSignOut.failure?.let { failure ->
                    AlertDialog(
                        onDismissRequest = { expectedPaymentsViewModel.stayAfterReceiptStorageFailure(current.user.uid) },
                        title = { Text("Receipt storage could not be saved") },
                        text = {
                            Text(failure + "\n\nUnsaved in-memory receipt changes may be lost. A receipt may already have posted. " +
                                "Signing out despite this failure does not promise persistence or cancel a receipt. " +
                                "Existing recovery bytes will be left unchanged by the override. Other income retry warnings still apply.")
                        },
                        confirmButton = {
                            TextButton(onClick = { expectedPaymentsViewModel.signOutDespiteReceiptStorageFailure(current.user.uid) }) {
                                Text("Sign out despite receipt-storage failure")
                            }
                        },
                        dismissButton = {
                            Column {
                                TextButton(onClick = { expectedPaymentsViewModel.retryReceiptSignOut(current.user.uid) }) { Text("Retry receipt persistence") }
                                TextButton(onClick = { expectedPaymentsViewModel.stayAfterReceiptStorageFailure(current.user.uid) }) { Text("Stay signed in") }
                            }
                        },
                    )
                }

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
                                if (visibleFinance.monthlyBills.hasUnresolvedSave) {
                                    if (isNotEmpty()) append("\n\n")
                                    append("A bill or payment may already have reached the server. Signing out discards " +
                                        "its retry details. Check Plan and Activity after signing back in before trying again.")
                                }
                                if (visibleGoals.hasUnresolvedWrite) {
                                    if (isNotEmpty()) append("\n\n")
                                    append("A goal or contribution may already have saved. Signing out clears its " +
                                        "retry details. Check Goals and Activity after signing back in.")
                                }
                                if (visibleIncome.hasUnresolvedIncomeWork) {
                                    if (isNotEmpty()) append("\n\n")
                                    append(
                                        "An uncertain income retry exists only in memory. " +
                                            "Signing out will discard its retry information, and " +
                                            "the income transaction may already have been recorded.",
                                    )
                                }
                                if (visibleIncome.hasUnresolvedSourceWork) {
                                    if (isNotEmpty()) append("\n\n")
                                    append(
                                        "An uncertain income-source retry exists only in memory. " +
                                            "Signing out will discard its retry information, and " +
                                        "the source may already have been created.",
                                    )
                                }
                                if (visibleExpectedPayments.receiptRecovery.hasWork) {
                                    if (isNotEmpty()) append("\n\n")
                                    append("Receipt drafts and retained operations remain on this device for this account. " +
                                        "Sign back in to review or explicitly retry them. Signing out does not cancel a receipt.")
                                }
                                if (visibleExpectedPayments.hasUnresolvedCancellationRetry) {
                                    if (isNotEmpty()) append("\n\n")
                                    append(
                                        "An uncertain expected-payment cancellation retry exists only in memory. " +
                                            "Signing out will discard its retry information, and the payment may already be cancelled.",
                                    )
                                }
                            })
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmSignOut = false
                                    expectedPaymentsViewModel.flushReceiptAndContinue(current.user.uid) {
                                        financeViewModel.flushAndSignOut(current.user.uid, viewModel::signOut)
                                    }
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
