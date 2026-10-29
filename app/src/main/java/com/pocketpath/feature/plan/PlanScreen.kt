package com.pocketpath.feature.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceStatus
import com.pocketpath.ui.components.lkrText
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun PlanScreen(
    state: FinanceUiState = FinanceUiState(),
    onEvent: (FinanceEvent) -> Unit = {},
) {
    val zone = ZoneId.of("Asia/Colombo")
    val today = LocalDate.now(zone)
    val ledger = CompleteLedger(state.activity)

    val activeBudgets = state.budgets.filter {
        it.state == BudgetState.ACTIVE &&
            !it.effectiveFrom.isAfter(today) &&
            (it.effectiveTo == null || !it.effectiveTo.isBefore(today))
    }.sortedWith(compareByDescending<Budget> { it.effectiveFrom }.thenBy { it.id })
    val overallBudget = activeBudgets.firstOrNull { it.scope == BudgetScope.OVERALL_DISCRETIONARY }
    val categoryBudgets = activeBudgets.filter { it.scope == BudgetScope.CATEGORY_DISCRETIONARY }

    val monthlyPeriod = budgetPeriodForDate(BudgetPeriod.MONTHLY, today, zone)
    val breakdown = calculateCommitmentBreakdown(ledger, monthlyPeriod)

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
                    "Planning & Budgeting",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FinanceStatus(state, onEvent)
            }
        }

        if (activeBudgets.isEmpty()) {
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
                    }
                }
            }
        } else {
            if (overallBudget != null) {
                item {
                    val progress = calculateBudgetProgress(ledger, overallBudget, today, zone)
                    OverallBudgetCard(progress)
                }
            }

            if (categoryBudgets.isNotEmpty()) {
                item {
                    Text(
                        "Category Spending Limits",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                categoryBudgets.forEach { budget ->
                    item {
                        val categoryName = state.categories.firstOrNull {
                            it.id == budget.categoryId
                        }?.name ?: "Category"

                        val catProgress = calculateBudgetProgress(ledger, budget, today, zone)
                        CategoryBudgetCard(categoryName, catProgress)
                    }
                }
            }
        }

        item {
            Text(
                "Monthly Spending Breakdown",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        item {
            SpendingBreakdownCard(breakdown)
        }
    }
}

@Composable
private fun OverallBudgetCard(progress: BudgetProgress) {
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
                    "Overall Discretionary Budget",
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
