package com.pocketpath.feature.finance

import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlanDashboardStateTest {

    private val time = SampleFixtures.time
    private val zone = time.recordedTimeZone
    private val today = LocalDate.of(2026, 10, 24)

    private fun expenseEvent(
        id: String,
        date: LocalDate,
        minor: Long,
        categoryId: String,
        commitmentClass: CommitmentClass,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): FinancialTransaction {
        val fTime = FinancialTime(date.atStartOfDay(zone).toInstant(), date, zone)
        return FinancialTransaction(
            operationId = id,
            kind = TransactionKind.EXPENSE,
            status = status,
            financialTime = fTime,
            clientCreatedAt = fTime.occurredAt,
            movements = listOf(
                AccountMovement("cash", NativeAmount.Fiat(Money.lkr(-minor))),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(Money.lkr(minor)),
                    categoryId = categoryId,
                    commitmentClass = commitmentClass,
                ),
            ),
            voidMetadata = if (status == TransactionStatus.VOIDED) VoidMetadata(fTime.occurredAt, "Voided") else null,
        )
    }

    @Test
    fun planDashboardDerivesOverallBudgetProgressAndCategoryProgressCorrectly() {
        val overallBudget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00L)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val diningBudget = Budget(
            id = "cat_b1",
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(10000_00L)),
            categoryId = "dining",
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val e1 = expenseEvent("e1", LocalDate.of(2026, 10, 5), 8000_00L, "dining", CommitmentClass.DISCRETIONARY)
        val e2 = expenseEvent("e2", LocalDate.of(2026, 10, 10), 12000_00L, "groceries", CommitmentClass.DISCRETIONARY)
        val e3Voided = expenseEvent("e3", LocalDate.of(2026, 10, 12), 15000_00L, "dining", CommitmentClass.DISCRETIONARY, status = TransactionStatus.VOIDED)

        val state = FinanceUiState(
            budgets = listOf(overallBudget, diningBudget),
            activity = listOf(e1, e2, e3Voided),
        )

        val ledger = CompleteLedger(state.activity)

        // Overall progress: 8,000 + 12,000 = 20,000 LKR (Voided 15,000 excluded)
        val overallProgress = calculateBudgetProgress(ledger, overallBudget, today, zone)
        assertEquals(20000_00L, overallProgress.spentLkrMinor)
        assertEquals(30000_00L, overallProgress.remainingLkrMinor)
        assertEquals(40.0, overallProgress.percentageUsed, 0.001)
        assertFalse(overallProgress.isOverBudget)

        // Dining category progress: 8,000 LKR (Voided 15,000 excluded)
        val diningProgress = calculateBudgetProgress(ledger, diningBudget, today, zone)
        assertEquals(8000_00L, diningProgress.spentLkrMinor)
        assertEquals(2000_00L, diningProgress.remainingLkrMinor)
        assertEquals(80.0, diningProgress.percentageUsed, 0.001)
        assertFalse(diningProgress.isOverBudget)
    }

    @Test
    fun effectiveDateFilteringExcludesFutureAndExpiredBudgets() {
        val pastBudget = Budget(
            id = "past_b",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(20000_00L)),
            effectiveFrom = LocalDate.of(2026, 8, 1),
            effectiveTo = LocalDate.of(2026, 8, 31),
        )

        val futureBudget = Budget(
            id = "future_b",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(30000_00L)),
            effectiveFrom = LocalDate.of(2026, 11, 1),
        )

        val activeBudget = Budget(
            id = "active_b",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00L)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val allBudgets = listOf(pastBudget, futureBudget, activeBudget)
        val filtered = allBudgets.filter {
            it.state == BudgetState.ACTIVE &&
                !it.effectiveFrom.isAfter(today) &&
                (it.effectiveTo == null || !it.effectiveTo.isBefore(today))
        }

        assertEquals(1, filtered.size)
        assertEquals("active_b", filtered.first().id)
    }
}
