package com.pocketpath.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.*

@Composable
fun AccountsScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onRecordIncome: () -> Unit,
    onManageIncomeSources: () -> Unit,
    onExpectedPayments: () -> Unit,
) {
    val form = state.accountForm
    val editable = state.canEditDraft && !form.busy && !form.locked && !form.saved

    FinanceBody(state, onEvent) {
        Text("New cash account", style = MaterialTheme.typography.headlineSmall)
        Text("Opening money increases your balance without counting as income.")

        OutlinedTextField(
            value = form.name,
            onValueChange = { onEvent(FinanceEvent.AccountNameChanged(it)) },
            label = { Text("Account name") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.opening,
            onValueChange = { onEvent(FinanceEvent.OpeningAmountChanged(it)) },
            label = { Text("Opening balance (LKR)") },
            supportingText = { Text("Use at most two decimal places, without commas.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.date,
            onValueChange = { onEvent(FinanceEvent.AccountDateChanged(it)) },
            label = { Text("Balance as of") },
            supportingText = { Text("yyyy-MM-dd") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.cached) {
            Text("You can prepare this draft offline. Connect to create the account.")
        }

        form.error?.let { FinanceMessage(it) }

        if (form.saved) {
            FinanceMessage("Account created.", error = false)

            OutlinedButton(
                onClick = { onEvent(FinanceEvent.NewAccount) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create another account")
            }
        } else {
            if (form.locked && !form.busy) {
                Text("Save is not confirmed. Retry without changing the entry.")
            }

            Button(
                onClick = { onEvent(FinanceEvent.CreateAccount) },
                enabled = state.ready && !state.cached && !form.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        form.busy -> "Saving…"
                        form.locked -> "Retry save"
                        else -> "Create cash account"
                    },
                )
            }
        }

        Text("Your accounts", style = MaterialTheme.typography.titleLarge)

        OutlinedButton(onClick = onRecordIncome, modifier = Modifier.fillMaxWidth()) {
            Text("Record income")
        }

        OutlinedButton(onClick = onManageIncomeSources, modifier = Modifier.fillMaxWidth()) {
            Text("Manage income sources")
        }

        OutlinedButton(onClick = onExpectedPayments, modifier = Modifier.fillMaxWidth()) {
            Text("Expected payments")
        }

        if (state.ready && state.accounts.isEmpty()) {
            Text("No accounts yet.")
        }

        state.accounts.forEach { account ->
            AccountBalanceCard(account, state.balances[account.id])
        }
    }
}
