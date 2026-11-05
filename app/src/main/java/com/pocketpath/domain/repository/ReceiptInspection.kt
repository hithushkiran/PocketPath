package com.pocketpath.domain.repository

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.FinancialTransaction

/** Read-only receipt review. Authoritative is one online, consistent operation/payment snapshot.
 * Cache-only implementations must return Unavailable, never authoritative absence.
 */
sealed interface ReceiptInspection {
    data class Authoritative(
        val owner: String,
        val operationId: String,
        val paymentId: String,
        val operation: FinancialTransaction?,
        val payment: ExpectedPayment?,
    ) : ReceiptInspection
    data class Unavailable(val failure: RepositoryFailure) : ReceiptInspection
}
