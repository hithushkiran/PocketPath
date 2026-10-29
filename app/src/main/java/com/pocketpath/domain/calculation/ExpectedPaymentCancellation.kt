package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState

/**
 * Pure cancellation decision used before a repository transaction writes an expected payment.
 * It deliberately has no account or ledger inputs: cancelling planning information cannot alter
 * actual balances or financial events.
 */
internal sealed interface ExpectedPaymentCancellationDecision {
    data object Apply : ExpectedPaymentCancellationDecision
    data object Replay : ExpectedPaymentCancellationDecision
    data object StaleRevision : ExpectedPaymentCancellationDecision
    data object ConflictingOperation : ExpectedPaymentCancellationDecision
    data object InvalidState : ExpectedPaymentCancellationDecision
}

internal fun expectedPaymentCancellationDecision(
    payment: ExpectedPayment,
    operationId: String,
    expectedRevision: Long,
): ExpectedPaymentCancellationDecision = when (payment.state) {
    ExpectedPaymentState.CANCELLED -> if (payment.cancellationOperationId == operationId) {
        ExpectedPaymentCancellationDecision.Replay
    } else {
        // A legacy cancelled document has no retained operation ID and must never be guessed as
        // a replay of a new operation.
        ExpectedPaymentCancellationDecision.ConflictingOperation
    }
    ExpectedPaymentState.ACTIVE -> if (payment.metadata.revision == expectedRevision) {
        ExpectedPaymentCancellationDecision.Apply
    } else {
        ExpectedPaymentCancellationDecision.StaleRevision
    }
}
