package com.pocketpath.domain

import com.pocketpath.domain.calculation.ExpectedPaymentReceiptAllocation
import com.pocketpath.domain.calculation.allocateExpectedPaymentReceipt
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.model.RecordMetadata
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ExpectedPaymentReceiptAllocationTest {
    private fun payment(received: Long = 0, revision: Long = 2, state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE) = ExpectedPayment(
        id = "payment", incomeSourceId = "source", projectLabel = "Project",
        amountDue = EntryAmount(Money.lkr(10_000)), dueDate = LocalDate.of(2026, 11, 1),
        state = state, cancelledAt = if (state == ExpectedPaymentState.CANCELLED) Instant.EPOCH else null,
        receivedAmountMinor = received, metadata = RecordMetadata(revision = revision),
    )

    @Test fun multiplePartialReceiptsKeepExactReceivedAndOutstandingMinorUnits() {
        assertEquals(ExpectedPaymentReceiptAllocation.Accepted(2_500, 7_500), allocateExpectedPaymentReceipt(payment(), 2, "source", Money.lkr(2_500)))
        assertEquals(ExpectedPaymentReceiptAllocation.Accepted(10_000, 0), allocateExpectedPaymentReceipt(payment(2_500), 2, "source", Money.lkr(7_500)))
    }

    @Test fun rejectsOverpaymentStaleCancelledAndIncompatibleReceipt() {
        assertEquals(ExpectedPaymentReceiptAllocation.Overpayment, allocateExpectedPaymentReceipt(payment(9_999), 2, "source", Money.lkr(2)))
        assertEquals(ExpectedPaymentReceiptAllocation.StaleRevision, allocateExpectedPaymentReceipt(payment(), 1, "source", Money.lkr(1)))
        assertEquals(ExpectedPaymentReceiptAllocation.Cancelled, allocateExpectedPaymentReceipt(payment(state = ExpectedPaymentState.CANCELLED), 2, "source", Money.lkr(1)))
        assertEquals(ExpectedPaymentReceiptAllocation.SourceMismatch, allocateExpectedPaymentReceipt(payment(), 2, "other", Money.lkr(1)))
        assertEquals(ExpectedPaymentReceiptAllocation.CurrencyMismatch, allocateExpectedPaymentReceipt(payment(), 2, "source", Money(FiatCurrency.USD, 1)))
    }
}
