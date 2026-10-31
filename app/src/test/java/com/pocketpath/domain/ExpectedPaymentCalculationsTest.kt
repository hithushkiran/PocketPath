package com.pocketpath.domain

import com.pocketpath.domain.calculation.activeExpectedPaymentTotalsByCurrency
import com.pocketpath.domain.calculation.activeExpectedPaymentsDueIn
import com.pocketpath.domain.calculation.activeExpectedPaymentOutstandingTotalsByCurrency
import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
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

    @Test fun outstandingTotalsUsePostedLinkedReceiptsAndExcludeCancelledPayments() {
        val dueDate = LocalDate.of(2026, 10, 10)
        val unpaid = payment("unpaid", dueDate, minor = 10_000L)
        val partial = payment("partial", dueDate, minor = 10_000L).copy(receivedAmountMinor = 2_500L)
        val paid = payment("paid", dueDate, minor = 5_000L).copy(receivedAmountMinor = 5_000L)
        val cancelled = payment("cancelled", dueDate, minor = 7_000L, state = ExpectedPaymentState.CANCELLED)
            .copy(receivedAmountMinor = 1_000L, lastReceiptOperationId = "cancelled-receipt")
        val ledger = CompleteLedger(listOf(
            receipt("partial-receipt", partial, 2_500L),
            receipt("paid-receipt", paid, 5_000L),
            receipt("cancelled-receipt", cancelled, 1_000L),
        ))

        assertEquals(
            Money.lkr(17_500L),
            activeExpectedPaymentOutstandingTotalsByCurrency(
                listOf(unpaid, partial, paid, cancelled),
                ledger,
            ).getValue(FiatCurrency.LKR),
        )
    }

    @Test fun outstandingTotalsRejectCounterDriftAndDuplicateReceiptIdentity() {
        val dueDate = LocalDate.of(2026, 10, 10)
        val partial = payment("partial", dueDate, minor = 10_000L).copy(receivedAmountMinor = 2_500L)
        val receipt = receipt("receipt", partial, 2_500L)

        assertThrows(IllegalArgumentException::class.java) {
            activeExpectedPaymentOutstandingTotalsByCurrency(
                listOf(partial.copy(receivedAmountMinor = 2_499L)),
                CompleteLedger(listOf(receipt)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompleteLedger(listOf(receipt, receipt))
        }
    }

    private fun receipt(id: String, payment: ExpectedPayment, minor: Long): FinancialTransaction {
        val currency = payment.amountDue.money.currency
        val amount = Money(currency, minor)
        val occurredAt = Instant.parse("2026-10-10T12:00:00Z")
        val date = LocalDate.of(2026, 10, 10)
        return FinancialTransaction(
            operationId = id,
            kind = TransactionKind.INCOME,
            status = TransactionStatus.POSTED,
            financialTime = FinancialTime(occurredAt, date, ZoneId.of("UTC")),
            clientCreatedAt = occurredAt,
            movements = listOf(AccountMovement("account", NativeAmount.Fiat(amount))),
            components = listOf(
                ReportComponent(
                    ComponentNature.EARNING,
                    KnownLkrAmount.Known(Money.lkr(minor)),
                    incomeSourceId = payment.incomeSourceId,
                ),
            ),
            expectedPaymentId = payment.id,
        )
    }
}
