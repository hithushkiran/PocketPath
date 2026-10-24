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
                    Text(title, style = MaterialTheme.typography.titleMedium)

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

                    if (event.kind == TransactionKind.OPENING) {
                        Text("Included in balance; excluded from income.")
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
}
