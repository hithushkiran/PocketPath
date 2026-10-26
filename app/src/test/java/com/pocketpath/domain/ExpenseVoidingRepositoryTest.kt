package com.pocketpath.domain

import com.pocketpath.data.sample.InMemoryFinanceRepository
import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.CreateAccountCommand
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.RecordExpenseCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

class ExpenseVoidingRepositoryTest {

    private val time = SampleFixtures.time
    private val zone = time.recordedTimeZone

    private fun repo() = InMemoryFinanceRepository(
        userId = "test-user-voiding",
        clock = Clock.fixed(time.occurredAt, zone),
        initialCategories = listOf(SampleFixtures.food),
    )

    private fun openingCommand(minor: Long = 10000_00L) = CreateAccountCommand(
        operationId = SampleFixtures.cash.openingTransactionId,
        account = SampleFixtures.cash,
        openingAmount = NativeAmount.Fiat(Money.lkr(minor)),
        financialTime = time,
        clientCreatedAt = time.occurredAt,
    )

    private fun expenseCommand(id: String = "expense_op_1", minor: Long = 2000_00L) = RecordExpenseCommand(
        operationId = id,
        accountId = SampleFixtures.cash.id,
        amount = EntryAmount(Money.lkr(minor)),
        categoryId = SampleFixtures.food.id,
        commitmentClass = CommitmentClass.DISCRETIONARY,
        financialTime = time,
        clientCreatedAt = time.occurredAt,
        note = "Lunch expense",
    )

    @Test
    fun successfulExpenseVoidingRestoresBalanceAndExcludesFromTotals() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand(10000_00L))
        val expenseResult = repository.recordExpense(expenseCommand("expense_1", 2000_00L))
        assertTrue(expenseResult is CommandResult.Accepted)
        val expenseId = (expenseResult as CommandResult.Accepted).value.id

        // Balance after expense = 8,000.00 LKR
        val balanceAfterExpense = (repository.observeBalance(SampleFixtures.cash.id).first() as Observation.Data).value
        assertEquals(8000_00L, (balanceAfterExpense.units as BalanceUnits.Fiat).minor)

        // Void the expense
        val voidResult = repository.voidExpense("void_op_1", expenseId, "Entered wrong amount")
        assertTrue(voidResult is CommandResult.Accepted)
        val voidedTx = (voidResult as CommandResult.Accepted).value
        assertEquals(TransactionStatus.VOIDED, voidedTx.status)
        assertEquals("Entered wrong amount", voidedTx.voidMetadata?.voidReason)

        // Balance restored to 10,000.00 LKR
        val balanceAfterVoid = (repository.observeBalance(SampleFixtures.cash.id).first() as Observation.Data).value
        assertEquals(10000_00L, (balanceAfterVoid.units as BalanceUnits.Fiat).minor)

        // Verify budget calculations and report totals exclude voided expense
        val ledger = (repository.observeEffectiveLedger().first() as Observation.Data).value
        val period = ReportPeriod(time.occurredLocalDate, time.occurredLocalDate, zone)
        val reportTotals = periodReportTotals(ledger, period)
        assertEquals(0L, reportTotals.netSpendingLkrMinor)

        val budget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(5000_00L)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )
        val progress = calculateBudgetProgress(ledger, budget, time.occurredLocalDate, zone)
        assertEquals(0L, progress.spentLkrMinor)
    }

    @Test
    fun sameOperationReplayReturnsAcceptedReplayed() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand())
        val expense = (repository.recordExpense(expenseCommand("expense_1")) as CommandResult.Accepted).value

        val void1 = repository.voidExpense("void_op_1", expense.id, "Mistake")
        assertTrue(void1 is CommandResult.Accepted)
        assertFalse((void1 as CommandResult.Accepted).replayed)

        val void2 = repository.voidExpense("void_op_1", expense.id, "Mistake")
        assertTrue(void2 is CommandResult.Accepted)
        assertTrue((void2 as CommandResult.Accepted).replayed)
    }

    @Test
    fun reVoidingAlreadyVoidedExpenseWithDifferentReasonOrOperationIsRejected() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand())
        val expense = (repository.recordExpense(expenseCommand("expense_1")) as CommandResult.Accepted).value

        repository.voidExpense("void_op_1", expense.id, "Mistake")

        val voidDifferentOp = repository.voidExpense("void_op_2", expense.id, "Different reason")
        assertTrue(voidDifferentOp is CommandResult.Rejected)
    }

    @Test
    fun voidingNonExpenseTransactionIsRejected() = runBlocking {
        val repository = repo()
        val account = (repository.createWithOpening(openingCommand()) as CommandResult.Accepted).value

        val voidResult = repository.voidExpense("void_op_1", account.openingTransactionId, "Cancel opening")
        assertTrue(voidResult is CommandResult.Rejected)
        assertEquals(FailureCode.VALIDATION, (voidResult as CommandResult.Rejected).failure.code)
    }

    @Test
    fun blankVoidReasonIsRejected() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand())
        val expense = (repository.recordExpense(expenseCommand("expense_1")) as CommandResult.Accepted).value

        val voidResult = repository.voidExpense("void_op_1", expense.id, "   ")
        assertTrue(voidResult is CommandResult.Rejected)
        assertEquals(FailureCode.VALIDATION, (voidResult as CommandResult.Rejected).failure.code)
    }

    @Test
    fun voidedExpensePreservesOriginalTransactionDetails() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand())
        val original = (repository.recordExpense(expenseCommand("expense_1", 3500_00L)) as CommandResult.Accepted).value

        val voidResult = repository.voidExpense("void_op_1", original.id, "Duplicate")
        val voided = (voidResult as CommandResult.Accepted).value

        assertEquals(original.id, voided.id)
        assertEquals(original.movements, voided.movements)
        assertEquals(original.components, voided.components)
        assertEquals(original.note, voided.note)
        assertEquals(original.financialTime, voided.financialTime)
        assertEquals(TransactionStatus.VOIDED, voided.status)
        assertEquals("Duplicate", voided.voidMetadata?.voidReason)
    }
}
