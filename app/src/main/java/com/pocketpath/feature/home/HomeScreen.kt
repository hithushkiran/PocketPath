package com.pocketpath.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.FundsPlanningResult
import com.pocketpath.domain.calculation.SavedGoalProgress
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.feature.goals.SavedGoalPaceDetails
import com.pocketpath.ui.components.*

@Composable
fun HomeScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onAccounts: () -> Unit,
    onAddExpense: () -> Unit,
    onAddIncome: () -> Unit,
    onGoals: () -> Unit,
    incomeSources: List<IncomeSource> = emptyList(),
    goalProgress: SavedGoalProgress? = null,
) {
    val plan = state.homeFunds
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    FinanceBody(state, onEvent, compact = true) {
        if (state.ready && state.accounts.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add your first account", style = type.headlineSmall)
                    Text("Enter your current balance to start tracking money.")
                    Button(onClick = onAccounts) { Text("Set up an account") }
                }
            }
        } else {
            FundsOverviewCard(state, plan)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAddExpense, modifier = Modifier.weight(1f)) {
                Text("Add expense", textAlign = TextAlign.Center)
            }
            FilledTonalButton(onClick = onAddIncome, modifier = Modifier.weight(1f)) {
                Text("Add income", textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onEvent(FinanceEvent.OpenPurchasePreview) },
                enabled = plan != null && state.selectableAccounts.isNotEmpty() &&
                    state.selectableCategories.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Check a purchase", textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onAccounts, modifier = Modifier.weight(1f)) {
                Text("Accounts", textAlign = TextAlign.Center)
            }
        }

        state.homeLedger?.let { summary ->
            var showSources by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This month", style = type.titleLarge)
                    Text("Through ${summary.date}", style = type.bodySmall, color = colors.onSurfaceVariant)
                    AmountRow("Income received", summary.receivedMinor, colors.secondary)
                    AmountRow("Net spending", summary.netSpendingMinor, colors.error)
                    summary.realizedCrypto?.let { realized ->
                        AmountRow(
                            "Realized crypto gain/loss",
                            realized.knownLkrMinor,
                            when {
                                realized.knownLkrMinor > 0L -> colors.secondary
                                realized.knownLkrMinor < 0L -> colors.error
                                else -> colors.onSurface
                            },
                        )
                        if (!realized.isComplete) Text("Some sale costs are unavailable.", style = type.bodySmall)
                    }
                    if (summary.receivedBySourceMinor.isNotEmpty()) {
                        TextButton(onClick = { showSources = !showSources }) {
                            Text(if (showSources) "Hide income by source" else "Income by source")
                        }
                        if (showSources) {
                            val labels = incomeSources.associate { it.id to it.name }
                            summary.receivedBySourceMinor.entries
                                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                                .forEach { (id, amount) -> AmountRow(labels[id] ?: "Source unavailable", amount) }
                            Text("See Accounts for outstanding expected payments.", style = type.bodySmall)
                        }
                    }
                }
            }
        }

        goalProgress?.let { progress ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Savings goal", style = type.labelLarge, color = colors.primary)
                    Text(progress.goal.name, style = type.titleLarge)
                    val target = progress.goal.target.money.minor
                    if (target > 0L) {
                        val fraction = (progress.confirmedReservedMinor.toDouble() / target).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Target ${lkrText(target)} · Due ${progress.goal.deadline}", style = type.bodyMedium)
                    SavedGoalPaceDetails(progress)
                    TextButton(onClick = onGoals) { Text("View goals") }
                }
            }
        }

        if (state.accounts.isNotEmpty()) {
            var showAccounts by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showAccounts = !showAccounts },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAccounts) "Hide account balances" else "Show account balances")
            }
            if (showAccounts) {
                state.accounts.forEach { account ->
                    AccountBalanceCard(account, state.balances[account.id])
                }
            }
        }
    }

    if (state.purchaseForm != null) PurchasePreviewDialog(state, plan, onEvent)
}

@Composable
private fun FundsOverviewCard(state: FinanceUiState, plan: FundsPlanningResult.Ready?) {
    var showBreakdown by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan == null) {
                Text("Everyday account balance", style = type.titleMedium)
                Text(state.poolBalanceMinor?.let(::lkrText) ?: "—", style = type.headlineLarge)
                if (!state.loading && state.error == null) {
                    val message = state.monthlyBills.error ?: state.monthlyBills.recurrenceError
                    FinanceMessage(message ?: "Available funds will appear when bill and account data are up to date.")
                }
            } else {
                val available = plan.availableAfterConfirmedReservationsMinor
                Text("Available to spend", style = type.titleMedium)
                Text(lkrText(available), style = type.headlineLarge)
                Text("After goals and bills through ${plan.input.horizonEnd}", style = type.bodyMedium)
                if (available < 0L) {
                    FinanceMessage("Recorded funds are short of current commitments and reservations.")
                }
                OutlinedButton(
                    onClick = { showBreakdown = !showBreakdown },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onPrimaryContainer),
                ) {
                    Text(if (showBreakdown) "Hide breakdown" else "How it’s calculated")
                }
                if (showBreakdown) {
                    HorizontalDivider()
                    AmountRow("Everyday LKR account balance", requireNotNull(plan.input.eligibleBalanceMinor))
                    AmountRow("Reserved for goals", requireNotNull(plan.input.reservedInEligibleAccountsMinor))
                    AmountRow("Unreserved before bills", plan.unreservedLiquidityMinor)
                    AmountRow("Bills still due", requireNotNull(plan.input.outstandingCommitmentsMinor))
                    Text("Includes overdue bills. Excludes expected income and future goal contributions.", style = type.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amountMinor: Long, amountColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            lkrText(amountMinor),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            color = amountColor,
            style = MaterialTheme.typography.titleSmall,
        )
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
