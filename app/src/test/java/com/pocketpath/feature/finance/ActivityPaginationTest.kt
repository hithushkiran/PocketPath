package com.pocketpath.feature.finance

import com.pocketpath.data.sample.InMemoryFinanceRepository
import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.ActivityQuery
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.CreateAccountCommand
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.RecordExpenseCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

class ActivityPaginationTest {

    private val time = SampleFixtures.time
    private val zone = time.recordedTimeZone

    private fun repo() = InMemoryFinanceRepository(
        userId = "test-user-pagination",
        clock = Clock.fixed(time.occurredAt, zone),
        initialCategories = listOf(SampleFixtures.food),
    )

    private fun openingCommand(minor: Long = 100000_00L) = CreateAccountCommand(
        operationId = SampleFixtures.cash.openingTransactionId,
        account = SampleFixtures.cash,
        openingAmount = NativeAmount.Fiat(Money.lkr(minor)),
        financialTime = time,
        clientCreatedAt = time.occurredAt,
    )

    @Test
    fun activityPaginationIncludesPostedAndVoidedTransactionsAcrossMultiplePages() = runBlocking {
        val repository = repo()
        repository.createWithOpening(openingCommand(100000_00L))

        // Record 60 expenses of LKR 1,000.00 each
        val recordedIds = mutableListOf<String>()
        for (i in 1..60) {
            val date = LocalDate.of(2026, 10, (i % 25) + 1)
            val fTime = FinancialTime(date.atStartOfDay(zone).toInstant(), date, zone)
            val cmd = RecordExpenseCommand(
                operationId = "exp_op_$i",
                accountId = SampleFixtures.cash.id,
                amount = EntryAmount(Money.lkr(1000_00L)),
                categoryId = SampleFixtures.food.id,
                commitmentClass = CommitmentClass.DISCRETIONARY,
                financialTime = fTime,
                clientCreatedAt = fTime.occurredAt,
            )
            val res = repository.recordExpense(cmd)
            assertTrue(res is CommandResult.Accepted)
            recordedIds.add((res as CommandResult.Accepted).value.id)
        }

        // Void 5 of the expenses
        val voidedIds = recordedIds.take(5)
        for (id in voidedIds) {
            val voidRes = repository.voidExpense("void_op_$id", id, "Entered in error")
            assertTrue(voidRes is CommandResult.Accepted)
        }

        // Page 1: Default 50 pageSize
        val page1Obs = repository.activityPage(ActivityQuery())
        assertTrue(page1Obs is Observation.Data)
        val page1 = (page1Obs as Observation.Data).value
        assertEquals(50, page1.events.size)
        assertNotNull(page1.nextCursor)

        // Verify page 1 contains both POSTED and VOIDED transactions
        val page1VoidedCount = page1.events.count { it.status == TransactionStatus.VOIDED }
        assertTrue("Page 1 should include voided records", page1VoidedCount > 0)

        // Page 2: Fetch using cursor from Page 1
        val page2Obs = repository.activityPage(ActivityQuery(cursor = page1.nextCursor))
        assertTrue(page2Obs is Observation.Data)
        val page2 = (page2Obs as Observation.Data).value
        // Opening transaction (1) + 60 expenses = 61 total transactions -> 50 on page 1 + 11 on page 2
        assertEquals(11, page2.events.size)
        assertNull("End of pagination reached", page2.nextCursor)

        // Verify no duplicate IDs between Page 1 and Page 2
        val page1Ids = page1.events.map { it.id }.toSet()
        val page2Ids = page2.events.map { it.id }.toSet()
        assertTrue(page1Ids.intersect(page2Ids).isEmpty())

        // Verify effective balances and reports exclude the 5 voided expenses
        // Net spending = 55 posted * 1,000 = 55,000 LKR (not 60,000 LKR)
        val ledger = (repository.observeEffectiveLedger().first() as Observation.Data).value
        assertEquals(56, ledger.events.size) // 1 opening + 55 posted expenses
        val period = ReportPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), zone)
        val totals = periodReportTotals(ledger, period)
        assertEquals(55000_00L, totals.netSpendingLkrMinor)
    }

    @Test
    fun emptyActivityPageReturnsZeroEventsAndNullCursor() = runBlocking {
        val repository = repo()
        val pageObs = repository.activityPage(ActivityQuery())
        assertTrue(pageObs is Observation.Data)
        val page = (pageObs as Observation.Data).value
        assertTrue(page.events.isEmpty())
        assertNull(page.nextCursor)
    }
}
