package com.pocketpath.domain

import com.pocketpath.domain.calculation.ExpectedPaymentCancellationDecision
import com.pocketpath.domain.calculation.expectedPaymentCancellationDecision
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

class ExpectedPaymentCancellationTest {
    private fun payment(
        state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE,
        revision: Long = 4L,
        cancellationOperationId: String? = null,
    ) = ExpectedPayment(
        id = "payment-1",
        incomeSourceId = "source-1",
        projectLabel = "Website",
        amountDue = EntryAmount(Money(FiatCurrency.LKR, 12_345L)),
        dueDate = LocalDate.of(2026, 11, 1),
        state = state,
        cancelledAt = if (state == ExpectedPaymentState.CANCELLED) Instant.EPOCH else null,
        cancellationOperationId = cancellationOperationId,
        metadata = RecordMetadata(revision = revision),
    )

    @Test fun activePaymentAppliesOnlyAtItsCurrentRevision() {
        assertEquals(
            ExpectedPaymentCancellationDecision.Apply,
            expectedPaymentCancellationDecision(payment(), "cancel-1", 4L),
        )
        assertEquals(
            ExpectedPaymentCancellationDecision.StaleRevision,
            expectedPaymentCancellationDecision(payment(), "cancel-1", 3L),
        )
    }

    @Test fun matchingCancellationOperationReplaysButDifferentOrLegacyOperationConflicts() {
        val cancelled = payment(
            state = ExpectedPaymentState.CANCELLED,
            revision = 5L,
            cancellationOperationId = "cancel-1",
        )
        assertEquals(
            ExpectedPaymentCancellationDecision.Replay,
            expectedPaymentCancellationDecision(cancelled, "cancel-1", 4L),
        )
        assertEquals(
            ExpectedPaymentCancellationDecision.ConflictingOperation,
            expectedPaymentCancellationDecision(cancelled, "cancel-2", 4L),
        )
        assertEquals(
            ExpectedPaymentCancellationDecision.ConflictingOperation,
            expectedPaymentCancellationDecision(
                payment(state = ExpectedPaymentState.CANCELLED, revision = 5L),
                "cancel-1",
                4L,
            ),
        )
    }
}
