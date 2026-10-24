package com.pocketpath.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.*

@Composable
fun HomeScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onAccounts: () -> Unit,
    onAddExpense: () -> Unit,
) {
    FinanceBody(state, onEvent) {
        Text(
            "Your recorded money",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (state.ready && state.accounts.isEmpty()) {
            Text("Create a cash account and enter the money you already have.")

            Button(
                onClick = onAccounts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set up an account")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Everyday account balance")
                    Text(
                        state.poolBalanceMinor?.let(::lkrText) ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text("Recorded balance across your designated LKR accounts.")
                }
            }

            Button(
                onClick = onAddExpense,
                enabled = state.ready && state.selectableAccounts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record an expense")
            }

            OutlinedButton(
                onClick = onAccounts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage accounts")
            }
        }

        state.accounts.forEach { account ->
            AccountBalanceCard(account, state.balances[account.id])
        }
    }
}
