package com.pocketpath.domain

import com.pocketpath.domain.calculation.activeExpectedPaymentTotalsByCurrency
import com.pocketpath.domain.calculation.activeExpectedPaymentsDueIn
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class ExpectedPaymentCalculationsTest {
    private fun payment(
        id: String,
        dueDate: LocalDate,
        currency: FiatCurrency = FiatCurrency.LKR,
        minor: Long = 1L,
        state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE,
    ) = ExpectedPayment(
        id = id,
        incomeSourceId = "freelance-source",
        projectLabel = "Project $id",
        amountDue = EntryAmount(Money(currency, minor)),
        dueDate = dueDate,
        state = state,
        cancelledAt = if (state == ExpectedPaymentState.CANCELLED) java.time.Instant.EPOCH else null,
    )

    @Test fun activePaymentsUseInclusivePlanningDateWindowAndStableOrder() {
        val start = LocalDate.of(2026, 10, 10)
        val due = activeExpectedPaymentsDueIn(
            listOf(
                payment("after", LocalDate.of(2026, 10, 21)),
                payment("same-b", LocalDate.of(2026, 10, 15)),
                payment("before", LocalDate.of(2026, 10, 9)),
                payment("same-a", LocalDate.of(2026, 10, 15)),
                payment("end", LocalDate.of(2026, 10, 20)),
                payment("cancelled", LocalDate.of(2026, 10, 12), state = ExpectedPaymentState.CANCELLED),
            ),
            start,
            LocalDate.of(2026, 10, 20),
        )

        assertEquals(listOf("same-a", "same-b", "end"), due.map(ExpectedPayment::id))
    }

    @Test fun totalsStayInNativeCurrencyAndExcludeCancelledPayments() {
        val totals = activeExpectedPaymentTotalsByCurrency(
            listOf(
                payment("lkr-one", LocalDate.of(2026, 10, 1), minor = 10_001L),
                payment("usd", LocalDate.of(2026, 10, 1), FiatCurrency.USD, 2_500L),
                payment("lkr-two", LocalDate.of(2026, 10, 2), minor = 99L),
                payment("cancelled", LocalDate.of(2026, 10, 3), minor = 1_000L, state = ExpectedPaymentState.CANCELLED),
            ),
        )

        assertEquals(listOf("LKR", "USD"), totals.keys.map(FiatCurrency::code))
        assertEquals(Money.lkr(10_100L), totals.getValue(FiatCurrency.LKR))
        assertEquals(Money(FiatCurrency.USD, 2_500L), totals.getValue(FiatCurrency.USD))
    }

    @Test fun queriesRejectDuplicateIdentityAndInvalidWindow() {
        val duplicate = payment("same", LocalDate.of(2026, 10, 10))

        assertThrows(IllegalArgumentException::class.java) {
            activeExpectedPaymentsDueIn(listOf(duplicate, duplicate), duplicate.dueDate, duplicate.dueDate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            activeExpectedPaymentTotalsByCurrency(listOf(duplicate, duplicate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            activeExpectedPaymentsDueIn(listOf(duplicate), duplicate.dueDate, duplicate.dueDate.minusDays(1))
        }
    }

    @Test fun queriesDoNotDependOnDeviceTimezone() {
        val original = TimeZone.getDefault()
        val payments = listOf(payment("due", LocalDate.of(2026, 10, 25), minor = 1_000L))
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val first = activeExpectedPaymentsDueIn(payments, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val second = activeExpectedPaymentsDueIn(payments, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25))

            assertEquals(first, second)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
