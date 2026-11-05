package com.pocketpath.feature.income

import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.model.IncomeSourceKind
import com.pocketpath.domain.money.FiatCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpectedPaymentCreationTest {
    private val source = IncomeSource("source", "Freelance", IncomeSourceKind.FREELANCE, defaultCurrency = FiatCurrency.LKR)
    @Test fun createsExactLkrPaymentWithRetainedOperationIdentity() {
        val payment = ExpectedPaymentCreationFactory.create("op", "source", " Website ", " Phase 1 ", "123.45", "2026-11-01", listOf(source))
        assertEquals("op", payment.id); assertEquals("Website", payment.projectLabel); assertEquals("Phase 1", payment.milestoneLabel); assertEquals(12_345L, payment.amountDue.money.minor)
    }
    @Test fun rejectsMissingSourceAndInvalidAmounts() {
        assertThrows(IllegalArgumentException::class.java) { ExpectedPaymentCreationFactory.create("op", null, "P", "", "1", "2026-11-01", listOf(source)) }
        assertThrows(IllegalArgumentException::class.java) { ExpectedPaymentCreationFactory.create("op", "source", "P", "", "0", "2026-11-01", listOf(source)) }
    }
    @Test fun lockedAndSavedStatesPreventDuplicateStarts() {
        assertEquals(false, ExpectedPaymentCreationUiState(locked = true).canStartSave())
        assertEquals(false, ExpectedPaymentCreationUiState(saved = true).canStartSave())
    }
}
