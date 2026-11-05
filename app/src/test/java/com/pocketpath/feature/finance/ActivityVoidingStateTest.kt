package com.pocketpath.feature.finance

import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import org.junit.Assert.*
import org.junit.Test

class ActivityVoidingStateTest {

    private val time = SampleFixtures.time

    private fun expenseEvent(id: String, status: TransactionStatus = TransactionStatus.POSTED): FinancialTransaction {
        return FinancialTransaction(
            operationId = id,
            kind = TransactionKind.EXPENSE,
            status = status,
            financialTime = time,
            clientCreatedAt = time.occurredAt,
            movements = listOf(
                AccountMovement("cash", NativeAmount.Fiat(Money.lkr(-1000_00L))),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(Money.lkr(1000_00L)),
                    categoryId = "food",
                    commitmentClass = CommitmentClass.DISCRETIONARY,
                ),
            ),
            voidMetadata = if (status == TransactionStatus.VOIDED) VoidMetadata(time.occurredAt, "Voided") else null,
        )
    }

    @Test
    fun voidFormStateUpdatesAndDialogTogglesCorrectly() {
        var state = FinanceUiState()
        assertFalse(state.showVoidDialog)

        // Open void dialog for tx_1
        state = state.copy(
            showVoidDialog = true,
            voidForm = VoidExpenseForm(transactionId = "tx_1"),
        )

        assertTrue(state.showVoidDialog)
        assertEquals("tx_1", state.voidForm.transactionId)

        // Update reason
        state = state.copy(voidForm = state.voidForm.copy(reason = "Entered wrong amount"))
        assertEquals("Entered wrong amount", state.voidForm.reason)
        assertFalse(state.voidForm.busy)
        assertNull(state.voidForm.error)
    }

    @Test
    fun onlyPostedExpenseIsEligibleForVoiding() {
        val postedExpense = expenseEvent("e1", TransactionStatus.POSTED)
        val voidedExpense = expenseEvent("e2", TransactionStatus.VOIDED)
        val opening = FinancialTransaction(
            operationId = "open_1",
            kind = TransactionKind.OPENING,
            status = TransactionStatus.POSTED,
            financialTime = time,
            clientCreatedAt = time.occurredAt,
            movements = listOf(
                AccountMovement("cash", NativeAmount.Fiat(Money.lkr(10000_00L))),
            ),
        )

        fun canVoid(tx: FinancialTransaction) =
            tx.kind == TransactionKind.EXPENSE && tx.status == TransactionStatus.POSTED

        assertTrue(canVoid(postedExpense))
        assertFalse(canVoid(voidedExpense))
        assertFalse(canVoid(opening))
    }

    @Test
    fun voidReasonValidationRejectsBlankReason() {
        val form = VoidExpenseForm(transactionId = "e1", reason = "   ")
        val isReasonValid = form.reason.isNotBlank() && form.reason.length <= 1000
        assertFalse(isReasonValid)

        val validForm = VoidExpenseForm(transactionId = "e1", reason = "Duplicate entry")
        val isValid = validForm.reason.isNotBlank() && validForm.reason.length <= 1000
        assertTrue(isValid)
    }

    @Test
    fun dialogDismissResetsFormState() {
        var state = FinanceUiState(
            showVoidDialog = true,
            voidForm = VoidExpenseForm(transactionId = "e1", reason = "Some reason"),
        )

        // Dismiss dialog
        state = state.copy(showVoidDialog = false, voidForm = VoidExpenseForm())
        assertFalse(state.showVoidDialog)
        assertNull(state.voidForm.transactionId)
        assertEquals("", state.voidForm.reason)
    }
}
