package com.pocketpath.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.FinanceStatus

@Composable
fun ActivityScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 24.dp,
            end = 24.dp,
            bottom = 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Recorded activity",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FinanceStatus(state, onEvent)
            }
        }

        if (state.activity.isEmpty() &&
            state.ready &&
            !state.activityLoading &&
            state.activityError == null
        ) {
            item { Text("No transactions yet.") }
        }

        items(state.activity, key = { it.id }) { event ->
            val title = when (event.kind) {
                TransactionKind.OPENING -> "Opening balance"
                TransactionKind.EXPENSE -> {
                    val categoryId = event.components.firstOrNull()?.categoryId
                    val name = state.categories.firstOrNull {
                        it.id == categoryId
                    }?.name
                    name?.let { "Expense · $it" } ?: "Expense"
                }

                else -> "Transaction"
            }

            val movement = event.movements.singleOrNull()
            val accountName = state.accounts.firstOrNull {
                it.id == movement?.accountId
            }?.name

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)

                        if (event.status == com.pocketpath.domain.model.TransactionStatus.VOIDED) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Voided") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            )
                        }
                    }

                    if (event.id in state.pendingExpenseIds) {
                        Text("Awaiting synchronization")
                    }

                    if (state.cached) {
                        Text("Cached activity")
                    }

                    movement?.amount?.let { amount ->
                        Text(
                            "${amount.asset.code} ${amount.major().toPlainString()}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }

                    accountName?.let { Text(it) }
                    Text(event.financialTime.occurredLocalDate.toString())
                    event.note?.let { Text(it) }

                    if (event.status == com.pocketpath.domain.model.TransactionStatus.VOIDED) {
                        event.voidMetadata?.voidReason?.let { reason ->
                            Text("Void reason: $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (event.kind == TransactionKind.OPENING) {
                        Text("Included in balance; excluded from income.")
                    }

                    if (event.kind == TransactionKind.EXPENSE &&
                        event.status == com.pocketpath.domain.model.TransactionStatus.POSTED
                    ) {
                        TextButton(
                            onClick = { onEvent(FinanceEvent.OpenVoidDialog(event.id)) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Void expense")
                        }
                    }
                }
            }
        }

        state.activityError?.let { message ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinanceMessage(message)
                    OutlinedButton(
                        onClick = { onEvent(FinanceEvent.ReloadActivity) },
                    ) {
                        Text("Reload Activity")
                    }
                }
            }
        }

        if (state.activityLoading) {
            item { CircularProgressIndicator() }
        }

        if (state.nextActivityCursor != null) {
            item {
                OutlinedButton(
                    onClick = { onEvent(FinanceEvent.LoadMoreActivity) },
                    enabled = state.ready && !state.activityLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load more")
                }
            }
        }
    }

    if (state.showVoidDialog) {
        val voidForm = state.voidForm
        val targetTx = state.activity.firstOrNull { it.id == voidForm.transactionId }

        AlertDialog(
            onDismissRequest = {
                if (!voidForm.busy) {
                    onEvent(FinanceEvent.ToggleVoidDialog(false))
                }
            },
            title = { Text("Void Expense") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    targetTx?.movements?.singleOrNull()?.amount?.let { amount ->
                        Text(
                            "Amount: ${amount.asset.code} ${amount.major().toPlainString()}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Text(
                        "Voiding this expense will restore your account balance and " +
                            "exclude it from budgets and totals, while preserving its record in history.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    OutlinedTextField(
                        value = voidForm.reason,
                        onValueChange = { onEvent(FinanceEvent.VoidReasonChanged(it)) },
                        label = { Text("Reason for voiding") },
                        supportingText = { Text("${voidForm.reason.length}/1000 characters") },
                        singleLine = true,
                        enabled = !voidForm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    voidForm.error?.let { FinanceMessage(it) }

                    if (voidForm.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(
                                androidx.compose.ui.Alignment.CenterHorizontally,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onEvent(FinanceEvent.SubmitVoidExpense) },
                    enabled = !voidForm.busy && voidForm.reason.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (voidForm.busy) "Voiding…" else "Void Expense")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(FinanceEvent.ToggleVoidDialog(false)) },
                    enabled = !voidForm.busy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
