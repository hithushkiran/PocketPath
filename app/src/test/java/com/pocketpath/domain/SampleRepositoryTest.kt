package com.pocketpath.domain

import com.pocketpath.data.sample.*
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock

class SampleRepositoryTest {
    private val time = SampleFixtures.time
    private fun repo() = InMemoryFinanceRepository("explicit-test-user", Clock.fixed(time.occurredAt, time.recordedTimeZone), listOf(SampleFixtures.food))
    private fun opening() = CreateAccountCommand("sample-opening", SampleFixtures.cash,
        NativeAmount.Fiat(Money.parse("10000", FiatCurrency.LKR)), time, time.occurredAt)
    private fun expense() = RecordExpenseCommand("expense", SampleFixtures.cash.id, EntryAmount(Money.parse("850", FiatCurrency.LKR)),
        SampleFixtures.food.id, CommitmentClass.DISCRETIONARY, time, time.occurredAt)

    @Test fun retriesCreateOneOpeningAndOneExpenseAndConflictingReuseFails() = runBlocking {
        val repository = repo()
        assertTrue(repository.createWithOpening(opening()) is CommandResult.Accepted)
        assertTrue((repository.createWithOpening(opening()) as CommandResult.Accepted).replayed)
        assertTrue(repository.recordExpense(expense()) is CommandResult.Accepted)
        assertTrue((repository.recordExpense(expense()) as CommandResult.Accepted).replayed)
        val conflicting = repository.recordExpense(expense().copy(amount = EntryAmount(Money.lkr(1)))) as CommandResult.Rejected
        assertEquals(FailureCode.CONFLICT, conflicting.failure.code)
        assertEquals(2, (repository.observeEffectiveLedger().first() as Observation.Data).value.events.size)
        assertEquals(915000L, ((repository.observeBalance(SampleFixtures.cash.id).first() as Observation.Data).value.units as BalanceUnits.Fiat).minor)
        assertEquals(WriteState.LOCAL_SAVED, (repository.observeAccounts().first() as Observation.Data).metadata.writeState)
    }

    @Test fun concurrentSameOperationDoesNotDoublePost() = runBlocking {
        val repository = repo()
        repository.createWithOpening(opening())
        val results = (1..20).map { async(Dispatchers.Default) { repository.recordExpense(expense()) } }.awaitAll()
        assertTrue(results.all { it is CommandResult.Accepted })
        assertEquals(1, results.filterIsInstance<CommandResult.Accepted<*>>().count { !it.replayed })
        assertEquals(2, (repository.observeEffectiveLedger().first() as Observation.Data).value.events.size)
    }

    @Test fun rejectedCommandIsAtomicAndRetryCanBeCorrected() = runBlocking {
        val repository = repo()
        val wrongAsset = opening().copy(openingAmount = NativeAmount.Fiat(Money(FiatCurrency.USD, 100)))
        assertTrue(repository.createWithOpening(wrongAsset) is CommandResult.Rejected)
        assertTrue((repository.observeAccounts().first() as Observation.Data).value.isEmpty())
        assertTrue((repository.observeEffectiveLedger().first() as Observation.Data).value.events.isEmpty())
        assertTrue(repository.createWithOpening(opening()) is CommandResult.Accepted)
        assertTrue(repository.createWithOpening(opening().copy(operationId = "different")) is CommandResult.Rejected)
    }

    @Test fun insufficientBalanceDoesNotRejectTruthfulExpense() = runBlocking {
        val repository = repo()
        repository.createWithOpening(opening().copy(openingAmount = NativeAmount.Fiat(Money.lkr(0))))
        assertTrue(repository.recordExpense(expense()) is CommandResult.Accepted)
        val balance = (repository.observeBalance(SampleFixtures.cash.id).first() as Observation.Data).value
        assertEquals(setOf(BalanceWarning.NEGATIVE_RECORDED_BALANCE), balance.warnings)
    }

    @Test fun sampleUsersAreIsolatedAndEmptyIsLoaded() = runBlocking {
        val seeded = SampleFixtures.create("user-one")
        val empty = InMemoryFinanceRepository("user-two", Clock.fixed(time.occurredAt, time.recordedTimeZone))
        assertEquals(1, (seeded.observeAccounts().first() as Observation.Data).value.size)
        assertTrue((empty.observeAccounts().first() as Observation.Data).value.isEmpty())
        assertNull((empty.findAccount(SampleFixtures.cash.id) as Observation.Data).value)
        assertTrue(empty.recordExpense(expense()) is CommandResult.Rejected)
    }

    @Test fun displayedPageIsIncompleteAndDoesNotAffectCompleteBalance() = runBlocking {
        val repository = SampleFixtures.create("explicit-test-user")
        val first = repository.activityPage(ActivityQuery(pageSize = 1)) as Observation.Data
        assertEquals(1, first.value.events.size)
        assertEquals(HistoryCompleteness.INCOMPLETE, first.metadata.completeness)
        assertNotNull(first.value.nextCursor)
        val second = repository.activityPage(ActivityQuery(pageSize = 1, cursor = first.value.nextCursor)) as Observation.Data
        assertEquals(1, second.value.events.size)
        assertNull(second.value.nextCursor)
        assertEquals(915000L, ((repository.observeBalance(SampleFixtures.cash.id).first() as Observation.Data).value.units as BalanceUnits.Fiat).minor)
        repository.recordExpense(expense())
        assertTrue(repository.activityPage(ActivityQuery(cursor = first.value.nextCursor)) is Observation.Failed)
    }

    @Test fun foreignExpenseRequiresExplicitHistoricalRateAndArchivedCategoriesStayReferenced() = runBlocking {
        val repository = repo()
        repository.createWithOpening(opening())
        repository.recordExpense(expense())
        val category = SampleFixtures.food.copy(metadata = RecordMetadata(revision = 1, archivedAt = time.occurredAt))
        assertTrue(repository.saveCategory("archive", category) is CommandResult.Accepted)
        assertTrue(repository.recordExpense(expense().copy(operationId = "new-expense")) is CommandResult.Rejected)
        assertEquals(2, (repository.observeEffectiveLedger().first() as Observation.Data).value.events.size)
        val usd = SampleFixtures.cash.copy(id = "usd", asset = FiatCurrency.USD, includeInEverydayPool = false, openingTransactionId = "usd-open")
        repository.createWithOpening(opening().copy(operationId = "usd-open", account = usd,
            openingAmount = NativeAmount.Fiat(Money(FiatCurrency.USD, 10000))))
        val fresh = Category("fresh", "Fresh", CommitmentClass.DISCRETIONARY)
        repository.saveCategory("fresh-category", fresh)
        assertTrue(repository.recordExpense(expense().copy(operationId = "usd-spend", accountId = "usd", categoryId = fresh.id,
            amount = EntryAmount(Money(FiatCurrency.USD, 100)))) is CommandResult.Rejected)
        val saved = repository.recordExpense(expense().copy(operationId = "usd-spend", accountId = "usd", categoryId = fresh.id,
            amount = EntryAmount(Money(FiatCurrency.USD, 100)), historicalRate = ValuationRate.fromDecimalString("300"))) as CommandResult.Accepted
        assertEquals(30000L, (saved.value.components.single().lkrAmount as KnownLkrAmount.Known).amount.minor)
        assertEquals(-30000L, saved.value.valuations.single().lkrAmount.minor)
        assertEquals(9900L, ((repository.observeBalance("usd").first() as Observation.Data).value.units as BalanceUnits.Fiat).minor)
    }
}
