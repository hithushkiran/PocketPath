package com.pocketpath.data.sample

import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic fixtures only. Explicitly opt in from development/test code, never production DI/UI. */
object SampleFixtures {
    val instant: Instant = Instant.parse("2026-10-01T06:00:00Z")
    val time = FinancialTime(instant, LocalDate.of(2026, 10, 1), ZoneId.of("Asia/Colombo"))
    val food = Category("sample-food", "Sample food", CommitmentClass.DISCRETIONARY)
    val cash = Account("sample-cash", "Sample cash", AccountType.CASH, FiatCurrency.LKR, true,
        "sample-opening", time.occurredLocalDate)

    suspend fun create(userId: String): InMemoryFinanceRepository {
        val repository = InMemoryFinanceRepository(userId, Clock.fixed(instant, time.recordedTimeZone), listOf(food))
        check(repository.createWithOpening(CreateAccountCommand("sample-opening", cash,
            NativeAmount.Fiat(Money.parse("10000", FiatCurrency.LKR)), time, instant)) is CommandResult.Accepted)
        check(repository.recordExpense(RecordExpenseCommand("sample-expense", cash.id,
            EntryAmount(Money.parse("850", FiatCurrency.LKR)), food.id, CommitmentClass.DISCRETIONARY,
            time, instant)) is CommandResult.Accepted)
        return repository
    }
}
