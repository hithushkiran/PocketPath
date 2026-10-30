package com.pocketpath.feature.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.FinanceStatus
import com.pocketpath.ui.components.lkrText
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun PlanScreen(
    state: FinanceUiState = FinanceUiState(),
    onEvent: (FinanceEvent) -> Unit = {},
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Planning & Budgeting",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedButton(
                        onClick = { onEvent(FinanceEvent.ToggleBudgetDialog(true)) },
                        enabled = state.ready,
                    ) {
                        Text("Set Budget")
                    }
                }
                FinanceStatus(state, onEvent)
            }
        }

        val hasBudgets = state.overallMonthlyBudgetProgress != null ||
            state.overallWeeklyBudgetProgress != null ||
            state.categoryBudgetProgresses.isNotEmpty()

        if (!hasBudgets) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "No budgets set yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Set weekly or monthly spending limits to track your " +
                                "discretionary expenses and keep your spending under control.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { onEvent(FinanceEvent.ToggleBudgetDialog(true)) },
                            enabled = state.ready,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Set a budget")
                        }
                    }
                }
            }
        } else {
            state.overallMonthlyBudgetProgress?.let { progress ->
                item {
                    OverallBudgetCard("Overall Monthly Discretionary Budget", progress)
                }
            }

            state.overallWeeklyBudgetProgress?.let { progress ->
                item {
                    OverallBudgetCard("Overall Weekly Discretionary Budget", progress)
                }
            }

            if (state.categoryBudgetProgresses.isNotEmpty()) {
                item {
                    Text(
                        "Category Spending Limits",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                state.categoryBudgetProgresses.forEach { (categoryName, progress) ->
                    item {
                        CategoryBudgetCard(categoryName, progress)
                    }
                }
            }
        }

        item {
            Text(
                "Monthly Spending Breakdown (Current Month)",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        state.monthlyCommitmentBreakdown?.let { breakdown ->
            item {
                SpendingBreakdownCard(breakdown)
            }
        }
    }

    if (state.showBudgetDialog) {
        val bForm = state.budgetForm
        AlertDialog(
            onDismissRequest = {
                if (!bForm.busy) {
                    onEvent(FinanceEvent.ToggleBudgetDialog(false))
                }
            },
            title = { Text("Set Spending Budget") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Budget Scope", style = MaterialTheme.typography.titleSmall)

                    Column(modifier = Modifier.selectableGroup()) {
                        BudgetScope.entries.forEach { scope ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = bForm.scope == scope,
                                        enabled = !bForm.busy,
                                        role = Role.RadioButton,
                                        onClick = { onEvent(FinanceEvent.BudgetScopeChanged(scope)) },
                                    ),
                            ) {
                                RadioButton(
                                    selected = bForm.scope == scope,
                                    onClick = null,
                                    enabled = !bForm.busy,
                                )
                                Text(
                                    text = when (scope) {
                                        BudgetScope.OVERALL_DISCRETIONARY -> "Overall Discretionary"
                                        BudgetScope.CATEGORY_DISCRETIONARY -> "Category Discretionary"
                                    },
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                    }

                    if (bForm.scope == BudgetScope.CATEGORY_DISCRETIONARY) {
                        FinanceChoice(
                            label = "Category",
                            selectedId = bForm.categoryId,
                            options = state.selectableCategories.map { it.id to it.name },
                            enabled = !bForm.busy,
                            onSelected = { onEvent(FinanceEvent.BudgetCategoryChanged(it)) },
                        )
                    }

                    Text("Budget Period", style = MaterialTheme.typography.titleSmall)

                    Column(modifier = Modifier.selectableGroup()) {
                        BudgetPeriod.entries.forEach { period ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = bForm.periodType == period,
                                        enabled = !bForm.busy,
                                        role = Role.RadioButton,
                                        onClick = { onEvent(FinanceEvent.BudgetPeriodChanged(period)) },
                                    ),
                            ) {
                                RadioButton(
                                    selected = bForm.periodType == period,
                                    onClick = null,
                                    enabled = !bForm.busy,
                                )
                                Text(
                                    text = when (period) {
                                        BudgetPeriod.MONTHLY -> "Monthly"
                                        BudgetPeriod.WEEKLY -> "Weekly"
                                    },
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = bForm.limitAmount,
                        onValueChange = { onEvent(FinanceEvent.BudgetLimitChanged(it)) },
                        label = { Text("Spending limit (LKR)") },
                        supportingText = { Text("Use at most two decimal places.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !bForm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    bForm.error?.let { FinanceMessage(it) }

                    if (bForm.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onEvent(FinanceEvent.SubmitBudget) },
                    enabled = !bForm.busy && bForm.limitAmount.isNotBlank(),
                ) {
                    Text(if (bForm.busy) "Saving…" else "Save Budget")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(FinanceEvent.ToggleBudgetDialog(false)) },
                    enabled = !bForm.busy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun OverallBudgetCard(title: String, progress: BudgetProgress) {
    val visualProgress = (progress.percentageUsed / 100.0).toFloat().coerceIn(0f, 1f)
    val indicatorColor = when {
        progress.isOverBudget -> MaterialTheme.colorScheme.error
        progress.percentageUsed >= 80.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (progress.isOverBudget) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Over Budget") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }

            Text(
                "${lkrText(progress.spentLkrMinor)} / ${lkrText(progress.limitLkrMinor)}",
                style = MaterialTheme.typography.headlineSmall,
            )

            LinearProgressIndicator(
                progress = { visualProgress },
                color = indicatorColor,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (progress.isOverBudget) {
                        "${lkrText(-progress.remainingLkrMinor)} over limit"
                    } else {
                        "${lkrText(progress.remainingLkrMinor)} remaining"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (progress.isOverBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "${progress.percentageUsed.toInt()}% used",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CategoryBudgetCard(categoryName: String, progress: BudgetProgress) {
    val visualProgress = (progress.percentageUsed / 100.0).toFloat().coerceIn(0f, 1f)
    val indicatorColor = when {
        progress.isOverBudget -> MaterialTheme.colorScheme.error
        progress.percentageUsed >= 80.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(categoryName, style = MaterialTheme.typography.titleMedium)
                if (progress.isOverBudget) {
                    Text(
                        "Over limit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                "${lkrText(progress.spentLkrMinor)} of ${lkrText(progress.limitLkrMinor)}",
                style = MaterialTheme.typography.bodyLarge,
            )

            LinearProgressIndicator(
                progress = { visualProgress },
                color = indicatorColor,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (progress.isOverBudget) {
                        "${lkrText(-progress.remainingLkrMinor)} over limit"
                    } else {
                        "${lkrText(progress.remainingLkrMinor)} remaining"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.isOverBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "${progress.percentageUsed.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SpendingBreakdownCard(breakdown: CommitmentBreakdown) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Total Month Spending",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    lkrText(breakdown.totalSpentLkrMinor),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            HorizontalDivider()

            CommitmentClass.entries.forEach { cls ->
                val clsTotal = breakdown.totals[cls]
                val spent = clsTotal?.spentLkrMinor ?: 0L
                val pct = clsTotal?.percentageOfTotal ?: 0.0

                val title = when (cls) {
                    CommitmentClass.COMMITTED -> "Committed"
                    CommitmentClass.DISCRETIONARY -> "Discretionary"
                    CommitmentClass.UNPLANNED_ESSENTIAL -> "Unplanned essential"
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(lkrText(spent), style = MaterialTheme.typography.bodyLarge)
                    }

                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        "${pct.toInt()}% of total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
