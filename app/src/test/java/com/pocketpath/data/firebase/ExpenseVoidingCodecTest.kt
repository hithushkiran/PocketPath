package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import org.junit.Assert.*
import org.junit.Test

class ExpenseVoidingCodecTest {

    private val audit = Timestamp(1_791_000_000L, 0)
    private val time = SampleFixtures.time

    private fun postedExpense(id: String = "expense_1", minor: Long = 5000_00L): FinancialTransaction {
        return FinancialTransaction(
            operationId = id,
            kind = TransactionKind.EXPENSE,
            status = TransactionStatus.POSTED,
            financialTime = time,
            clientCreatedAt = time.occurredAt,
            movements = listOf(
                AccountMovement("cash", NativeAmount.Fiat(Money.lkr(-minor))),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(Money.lkr(minor)),
                    categoryId = "food",
                    commitmentClass = CommitmentClass.DISCRETIONARY,
                ),
            ),
        )
    }

    private fun voidedExpense(id: String = "expense_2", minor: Long = 5000_00L, reason: String = "Duplicate entry"): FinancialTransaction {
        val base = postedExpense(id, minor)
        return FinancialTransaction(
            operationId = base.operationId,
            kind = base.kind,
            status = TransactionStatus.VOIDED,
            financialTime = base.financialTime,
            clientCreatedAt = base.clientCreatedAt,
            movements = base.movements,
            components = base.components,
            voidMetadata = VoidMetadata(time.occurredAt, reason),
        )
    }

    @Test
    fun postedExpenseEncodesAndDecodesCorrectly() {
        val expense = postedExpense()
        val data = FoundationCodec.eventData(expense) + ("createdAt" to audit)

        val decoded = FoundationCodec.event(expense.id, data)

        assertEquals(expense.id, decoded.id)
        assertEquals(TransactionStatus.POSTED, decoded.status)
        assertNull(decoded.voidMetadata)
        assertNull(decoded.replacesTransactionId)
    }

    @Test
    fun voidedExpenseEncodesAndDecodesCorrectly() {
        val expense = voidedExpense(reason = "Entered wrong amount")
        val data = FoundationCodec.eventData(expense) + ("createdAt" to audit)

        val decoded = FoundationCodec.event(expense.id, data)

        assertEquals(expense.id, decoded.id)
        assertEquals(TransactionStatus.VOIDED, decoded.status)
        assertNotNull(decoded.voidMetadata)
        assertEquals("Entered wrong amount", decoded.voidMetadata?.voidReason)
        assertEquals(time.occurredAt, decoded.voidMetadata?.voidedAt)
    }

    @Test
    fun replacementTransactionEncodesAndDecodesReplacesTransactionId() {
        val replacement = FinancialTransaction(
            operationId = "replacement_1",
            kind = TransactionKind.EXPENSE,
            status = TransactionStatus.POSTED,
            financialTime = time,
            clientCreatedAt = time.occurredAt,
            movements = listOf(
                AccountMovement("cash", NativeAmount.Fiat(Money.lkr(-6000_00L))),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(Money.lkr(6000_00L)),
                    categoryId = "food",
                    commitmentClass = CommitmentClass.DISCRETIONARY,
                ),
            ),
            replacesTransactionId = "expense_1",
        )

        val data = FoundationCodec.eventData(replacement) + ("createdAt" to audit)
        val decoded = FoundationCodec.event(replacement.id, data)

        assertEquals("replacement_1", decoded.id)
        assertEquals(TransactionStatus.POSTED, decoded.status)
        assertEquals("expense_1", decoded.replacesTransactionId)
    }

    @Test
    fun voidedStatusWithoutVoidMetadataIsRejected() {
        val expense = postedExpense()
        val data = FoundationCodec.eventData(expense) +
            ("status" to "VOIDED") +
            ("createdAt" to audit)

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(expense.id, data)
        }
    }

    @Test
    fun postedStatusWithVoidMetadataIsRejected() {
        val expense = postedExpense()
        val data = FoundationCodec.eventData(expense) +
            ("voidMetadata" to mapOf("voidedAt" to audit, "voidReason" to "Invalid")) +
            ("createdAt" to audit)

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(expense.id, data)
        }
    }

    @Test
    fun blankVoidReasonIsRejected() {
        val expense = voidedExpense(reason = "Entered wrong amount")
        val data = FoundationCodec.eventData(expense) +
            ("voidMetadata" to mapOf("voidedAt" to audit, "voidReason" to "   ")) +
            ("createdAt" to audit)

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(expense.id, data)
        }
    }
}
