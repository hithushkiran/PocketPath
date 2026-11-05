package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.Money

/** Pure pre-transaction allocation check; Firestore rechecks this against its current snapshot. */
internal sealed interface ExpectedPaymentReceiptAllocation {
    data class Accepted(val receivedAmountMinor: Long, val outstandingAmountMinor: Long) : ExpectedPaymentReceiptAllocation
    data object StaleRevision : ExpectedPaymentReceiptAllocation
    data object Cancelled : ExpectedPaymentReceiptAllocation
    data object SourceMismatch : ExpectedPaymentReceiptAllocation
    data object CurrencyMismatch : ExpectedPaymentReceiptAllocation
    data object Overpayment : ExpectedPaymentReceiptAllocation
}

internal fun allocateExpectedPaymentReceipt(
    payment: ExpectedPayment,
    expectedRevision: Long,
    sourceId: String,
    amount: Money,
): ExpectedPaymentReceiptAllocation = when {
    payment.state != ExpectedPaymentState.ACTIVE -> ExpectedPaymentReceiptAllocation.Cancelled
    payment.metadata.revision != expectedRevision -> ExpectedPaymentReceiptAllocation.StaleRevision
    payment.incomeSourceId != sourceId -> ExpectedPaymentReceiptAllocation.SourceMismatch
    payment.amountDue.money.currency != amount.currency -> ExpectedPaymentReceiptAllocation.CurrencyMismatch
    else -> try {
        val received = Math.addExact(payment.receivedAmountMinor, amount.minor)
        if (amount.minor <= 0L || received > payment.amountDue.money.minor) {
            ExpectedPaymentReceiptAllocation.Overpayment
        } else {
            ExpectedPaymentReceiptAllocation.Accepted(received, payment.amountDue.money.minor - received)
        }
    } catch (_: ArithmeticException) {
        ExpectedPaymentReceiptAllocation.Overpayment
    }
}
