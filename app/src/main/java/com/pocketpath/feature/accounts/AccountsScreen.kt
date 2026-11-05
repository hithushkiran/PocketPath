package com.pocketpath.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    onIncomeReminders: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(AccountsSection.BALANCES) }
    Column(Modifier.fillMaxSize()) {
        SectionTabs(AccountsSection.entries.map { it.label }, section.ordinal) {
            section = AccountsSection.entries[it]
        }

        key(section) {
            when (section) {
                AccountsSection.BALANCES -> FinanceBody(state, onEvent, compact = true) {
                    Text("Your balances", style = MaterialTheme.typography.titleLarge)
                    if (state.accountForm.locked && !state.accountForm.saved) {
                        OutlinedButton(onClick = { section = AccountsSection.NEW_ACCOUNT }) {
                            Text("Review account save")
                        }
                    }
                    if (state.ready && state.accounts.isEmpty()) {
                        Text("Add a cash account to start tracking your money.")
                        Button(onClick = { section = AccountsSection.NEW_ACCOUNT }) {
                            Text("Create an account")
                        }
                    }
                    state.accounts.forEach { account ->
                        AccountBalanceCard(account, state.balances[account.id])
                    }
                }
                AccountsSection.NEW_ACCOUNT -> NewCashAccount(state, onEvent) {
                    section = AccountsSection.BALANCES
                }
                AccountsSection.INCOME -> FinanceBody(state, onEvent, compact = true) {
                    Text("Income tools", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onRecordIncome, modifier = Modifier.fillMaxWidth()) {
                        Text("Record income")
                    }
                    OutlinedButton(onClick = onManageIncomeSources, modifier = Modifier.fillMaxWidth()) {
                        Text("Manage income sources")
                    }
                    OutlinedButton(onClick = onExpectedPayments, modifier = Modifier.fillMaxWidth()) {
                        Text("Expected payments")
                    }
                    OutlinedButton(onClick = onIncomeReminders, modifier = Modifier.fillMaxWidth()) {
                        Text("Recurring income")
                    }
                }
            }
        }
    }
}

private enum class AccountsSection(val label: String) {
    BALANCES("Balances"),
    NEW_ACCOUNT("New account"),
    INCOME("Income"),
}

@Composable
private fun NewCashAccount(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onViewBalances: () -> Unit,
) {
    val form = state.accountForm
    val editable = state.canEditDraft && !form.busy && !form.locked && !form.saved

    Column(Modifier.fillMaxSize()) {
        FinanceBody(state, onEvent, modifier = Modifier.weight(1f), compact = true) {
            Text("New cash account", style = MaterialTheme.typography.titleLarge)
            Text("Enter the money already in this account.", style = MaterialTheme.typography.bodyMedium)

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
        }

        FormActions {
            form.error?.let { FinanceMessage(it) }

            if (form.saved) {
                FinanceMessage("Account created.", error = false)

                Button(onClick = onViewBalances, modifier = Modifier.fillMaxWidth()) {
                    Text("View balances")
                }

                TextButton(
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
        }
    }
}
