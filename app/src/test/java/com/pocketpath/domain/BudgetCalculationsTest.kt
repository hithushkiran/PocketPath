package com.pocketpath.domain

import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetCalculationsTest {

    private val zone = ZoneId.of("Asia/Colombo")
    private val asOf = LocalDate.of(2026, 10, 24) // October 24, 2026 (Saturday)

    private fun financialTime(date: LocalDate): FinancialTime =
        FinancialTime(date.atStartOfDay(zone).toInstant(), date, zone)

    private fun expenseEvent(
        id: String,
        date: LocalDate,
        minor: Long,
        categoryId: String,
        commitmentClass: CommitmentClass,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): FinancialTransaction {
        val time = financialTime(date)
        return FinancialTransaction(
            operationId = id,
            kind = TransactionKind.EXPENSE,
            status = status,
            financialTime = time,
            clientCreatedAt = time.occurredAt,
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
            voidMetadata = if (status == TransactionStatus.VOIDED) {
                VoidMetadata(time.occurredAt, "Cancelled expense")
            } else {
                null
            },
        )
    }

    @Test
    fun budgetPeriodForMonthlyAndWeeklyIsCorrect() {
        val monthlyPeriod = budgetPeriodForDate(BudgetPeriod.MONTHLY, asOf, zone)
        assertEquals(LocalDate.of(2026, 10, 1), monthlyPeriod.start)
        assertEquals(LocalDate.of(2026, 10, 31), monthlyPeriod.endInclusive)

        // Saturday Oct 24, 2026: week starting Monday (weekStartsOn = 1) is Oct 19..Oct 25
        val weeklyPeriod = budgetPeriodForDate(BudgetPeriod.WEEKLY, asOf, zone, weekStartsOn = 1)
        assertEquals(LocalDate.of(2026, 10, 19), weeklyPeriod.start)
        assertEquals(LocalDate.of(2026, 10, 25), weeklyPeriod.endInclusive)
    }

    @Test
    fun overallDiscretionaryBudgetCalculatesSpentAndOverBudgetCorrectly() {
        val budget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00)), // LKR 50,000.00
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val e1 = expenseEvent("e1", LocalDate.of(2026, 10, 5), 20000_00, "food", CommitmentClass.DISCRETIONARY)
        val e2 = expenseEvent("e2", LocalDate.of(2026, 10, 15), 15000_00, "transport", CommitmentClass.COMMITTED) // Excluded from discretionary
        val e3 = expenseEvent("e3", LocalDate.of(2026, 10, 20), 20000_00, "shopping", CommitmentClass.DISCRETIONARY)

        val ledger = CompleteLedger(listOf(e1, e2, e3))
        val progress = calculateBudgetProgress(ledger, budget, asOf, zone)

        assertEquals(40000_00L, progress.spentLkrMinor)
        assertEquals(50000_00L, progress.limitLkrMinor)
        assertEquals(10000_00L, progress.remainingLkrMinor)
        assertEquals(80.0, progress.percentageUsed, 0.001)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun voidedTransactionsAreExcludedFromBudgetProgress() {
        val budget = Budget(
            id = "overall_b2",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(30000_00)), // LKR 30,000.00
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val e1 = expenseEvent("e1", LocalDate.of(2026, 10, 5), 20000_00, "food", CommitmentClass.DISCRETIONARY)
        // Voided transaction should NOT contribute to spent amount
        val e2Voided = expenseEvent("e2", LocalDate.of(2026, 10, 10), 25000_00, "food", CommitmentClass.DISCRETIONARY, status = TransactionStatus.VOIDED)

        val ledger = CompleteLedger(listOf(e1, e2Voided))
        val progress = calculateBudgetProgress(ledger, budget, asOf, zone)

        assertEquals(20000_00L, progress.spentLkrMinor)
        assertEquals(10000_00L, progress.remainingLkrMinor)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun categoryDiscretionaryCountsOnlyDiscretionaryInTargetCategory() {
        val budget = Budget(
            id = "cat_b1",
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(10000_00)), // LKR 10,000.00
            categoryId = "dining",
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val e1 = expenseEvent("e1", LocalDate.of(2026, 10, 5), 8000_00, "dining", CommitmentClass.DISCRETIONARY)
        val e2 = expenseEvent("e2", LocalDate.of(2026, 10, 6), 4000_00, "dining", CommitmentClass.COMMITTED) // Same category, but COMMITTED -> excluded
        val e3 = expenseEvent("e3", LocalDate.of(2026, 10, 7), 5000_00, "groceries", CommitmentClass.DISCRETIONARY) // Different category -> excluded

        val ledger = CompleteLedger(listOf(e1, e2, e3))
        val progress = calculateBudgetProgress(ledger, budget, asOf, zone)

        assertEquals(8000_00L, progress.spentLkrMinor)
        assertEquals(2000_00L, progress.remainingLkrMinor)
        assertEquals(80.0, progress.percentageUsed, 0.001)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun weekStartsOnIsRespectedInWeeklyBudgetPeriod() {
        // Saturday Oct 24, 2026 with weekStartsOn = 7 (Sunday)
        // Week starts Sun Oct 18, ends Sat Oct 24
        val weeklyPeriodSun = budgetPeriodForDate(BudgetPeriod.WEEKLY, asOf, zone, weekStartsOn = 7)
        assertEquals(LocalDate.of(2026, 10, 18), weeklyPeriodSun.start)
        assertEquals(LocalDate.of(2026, 10, 24), weeklyPeriodSun.endInclusive)
    }

    @Test
    fun commitmentBreakdownCalculatesPercentagesAndExcludesVoided() {
        val period = ReportPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), zone)

        val eCommitted = expenseEvent("e1", LocalDate.of(2026, 10, 2), 50000_00, "rent", CommitmentClass.COMMITTED)
        val eDiscretionary = expenseEvent("e2", LocalDate.of(2026, 10, 10), 30000_00, "dining", CommitmentClass.DISCRETIONARY)
        val eUnplanned = expenseEvent("e3", LocalDate.of(2026, 10, 15), 20000_00, "medical", CommitmentClass.UNPLANNED_ESSENTIAL)
        val eVoided = expenseEvent("e4", LocalDate.of(2026, 10, 18), 100000_00, "dining", CommitmentClass.DISCRETIONARY, status = TransactionStatus.VOIDED)

        val ledger = CompleteLedger(listOf(eCommitted, eDiscretionary, eUnplanned, eVoided))
        val breakdown = calculateCommitmentBreakdown(ledger, period)

        assertEquals(100000_00L, breakdown.totalSpentLkrMinor)
        assertEquals(50000_00L, breakdown.totals[CommitmentClass.COMMITTED]?.spentLkrMinor)
        assertEquals(50.0, breakdown.totals[CommitmentClass.COMMITTED]!!.percentageOfTotal, 0.001)

        assertEquals(30000_00L, breakdown.totals[CommitmentClass.DISCRETIONARY]?.spentLkrMinor)
        assertEquals(30.0, breakdown.totals[CommitmentClass.DISCRETIONARY]!!.percentageOfTotal, 0.001)

        assertEquals(20000_00L, breakdown.totals[CommitmentClass.UNPLANNED_ESSENTIAL]?.spentLkrMinor)
        assertEquals(20.0, breakdown.totals[CommitmentClass.UNPLANNED_ESSENTIAL]!!.percentageOfTotal, 0.001)
    }
}
