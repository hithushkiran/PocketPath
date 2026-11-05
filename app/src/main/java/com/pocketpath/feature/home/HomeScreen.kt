package com.pocketpath.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.FundsPlanningResult
import com.pocketpath.domain.calculation.SavedGoalProgress
import com.pocketpath.feature.goals.SavedGoalPaceDetails
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.*

@Composable
fun HomeScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onAccounts: () -> Unit,
    onAddExpense: () -> Unit,
    incomeSources: List<IncomeSource> = emptyList(),
    goalProgress: SavedGoalProgress? = null,
) {
    val plan = state.homeFunds
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
            FundsOverviewCard(state, plan)
            OutlinedButton(
                onClick = { onEvent(FinanceEvent.OpenPurchasePreview) },
                enabled = plan != null && state.selectableAccounts.isNotEmpty() &&
                    state.selectableCategories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check a purchase")
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

        goalProgress?.let { progress ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Savings goal · ${progress.goal.name}", style = MaterialTheme.typography.titleMedium)
                    Text("Target ${lkrText(progress.goal.target.money.minor)} · Due ${progress.goal.deadline}")
                    SavedGoalPaceDetails(progress)
                }
            }
        }

        state.homeLedger?.let { summary ->
            Text("This month through ${summary.date}", style = MaterialTheme.typography.titleMedium)
            AmountRow("Income received", summary.receivedMinor)
            if (summary.receivedBySourceMinor.isNotEmpty()) {
                Text("Received by source", style = MaterialTheme.typography.titleSmall)
                val labels = incomeSources.associate { it.id to it.name }
                summary.receivedBySourceMinor.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    .forEach { (id, amount) ->
                        AmountRow(labels[id] ?: "Source unavailable", amount)
                    }
                Text("Outstanding expected payments are shown separately in Accounts.")
            }
            AmountRow("Net spending", summary.netSpendingMinor)
            summary.realizedCrypto?.let { realized ->
                AmountRow("Realized crypto gain/loss", realized.knownLkrMinor)
                if (!realized.isComplete) Text("Some sale costs are unavailable.")
            }
        }

        state.accounts.forEach { account ->
            AccountBalanceCard(account, state.balances[account.id])
        }
    }

    if (state.purchaseForm != null) PurchasePreviewDialog(state, plan, onEvent)
}

@Composable
private fun FundsOverviewCard(state: FinanceUiState, plan: FundsPlanningResult.Ready?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan == null) {
                Text("Everyday account balance", style = MaterialTheme.typography.titleMedium)
                Text(state.poolBalanceMinor?.let(::lkrText) ?: "—", style = MaterialTheme.typography.headlineMedium)
                if (!state.loading && state.error == null) {
                    val message = state.monthlyBills.error ?: state.monthlyBills.recurrenceError
                    FinanceMessage(message ?: "Available funds will appear when bill and account data are up to date.")
                }
            } else {
                Text("Available after goals and bills", style = MaterialTheme.typography.titleMedium)
                Text(lkrText(plan.availableAfterConfirmedReservationsMinor), style = MaterialTheme.typography.headlineMedium)
                if (plan.availableAfterConfirmedReservationsMinor < 0L) {
                    FinanceMessage("Recorded funds are short of current commitments and reservations.")
                }
                Text("Through ${plan.input.horizonEnd}, including overdue bills.")
                AmountRow("Everyday LKR account balance", requireNotNull(plan.input.eligibleBalanceMinor))
                AmountRow("Reserved for goals in these accounts", requireNotNull(plan.input.reservedInEligibleAccountsMinor))
                AmountRow("Unreserved before bills", plan.unreservedLiquidityMinor)
                AmountRow("Bills still due", requireNotNull(plan.input.outstandingCommitmentsMinor))
                Text("Future goal contributions and expected income are excluded.")
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amountMinor: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(lkrText(amountMinor))
    }
}

@Composable
private fun PurchasePreviewDialog(
    state: FinanceUiState,
    plan: FundsPlanningResult.Ready?,
    onEvent: (FinanceEvent) -> Unit,
) {
    val form = state.purchaseForm ?: return
    val result = state.purchaseResult(plan)
    val invalidAmount = form.amount.isNotBlank() && form.enteredAmount == null
    AlertDialog(
        onDismissRequest = { onEvent(FinanceEvent.ClosePurchasePreview) },
        title = { Text("Check a purchase") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Preview an extra purchase outside your existing bills.")
                FinanceChoice(
                    label = "Paying account",
                    selectedId = form.accountId,
                    options = state.selectableAccounts.map { it.id to it.name },
                    enabled = true,
                    onSelected = { onEvent(FinanceEvent.EditPurchasePreview(accountId = it)) },
                )
                FinanceChoice(
                    label = "Category",
                    selectedId = form.categoryId,
                    options = state.selectableCategories.map { it.id to it.name },
                    enabled = true,
                    onSelected = { onEvent(FinanceEvent.EditPurchasePreview(categoryId = it)) },
                )
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEvent(FinanceEvent.EditPurchasePreview(amount = it)) },
                    label = { Text("Purchase amount (LKR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = invalidAmount,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    invalidAmount -> FinanceMessage("Enter a positive LKR amount with at most two decimal places.")
                    plan == null -> Text("Waiting for current funds and bill data.")
                    result != null -> {
                        val impact = result.impact
                        AmountRow("Available before", impact.before.availableAfterConfirmedReservationsMinor)
                        AmountRow("Available after", impact.after.availableAfterConfirmedReservationsMinor)
                        AmountRow("Unreserved in paying account after", result.unreservedAccountBalanceAfterMinor)
                        if (!result.accountInEverydayPool) {
                            Text("This account is outside your everyday pool.")
                        }
                        if (impact.incrementalPlanningGapMinor > 0L) {
                            FinanceMessage("Adds ${lkrText(impact.incrementalPlanningGapMinor)} to your funding shortfall.")
                        }
                        if (result.unreservedAccountBalanceAfterMinor < 0L) {
                            FinanceMessage(if (result.reservedInAccountMinor > 0L) {
                                "This account would be short of the funds earmarked for goals."
                            } else {
                                "This account would have a negative balance."
                            })
                        }
                    }
                    form.amount.isNotBlank() -> Text("Select an available account and category.")
                }
                Text("Preview only. No transaction is saved.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(FinanceEvent.ClosePurchasePreview) }) { Text("Close") }
        },
    )
}
