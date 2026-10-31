package com.pocketpath.feature.income

import com.pocketpath.domain.model.IncomeSourceKind
import com.pocketpath.domain.money.FiatCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeSourceManagementTest {

    @Test fun createsTrimmedLkrSourceWithRetainedOperationIdentity() {
        val source = IncomeSourceFactory.create(
            operationId = "source-operation",
            nameText = "  Salary  ",
            kind = IncomeSourceKind.SALARY,
        )

        assertEquals("source-operation", source.id)
        assertEquals("Salary", source.name)
        assertEquals(IncomeSourceKind.SALARY, source.kind)
        assertEquals(FiatCurrency.LKR, source.defaultCurrency)
        assertNull(source.defaultAccountId)
    }

    @Test fun allowanceUsesExistingOtherKindWithoutNewEnum() {
        val source = IncomeSourceFactory.create("allowance-operation", "Allowance", IncomeSourceKind.OTHER)

        assertEquals("Allowance", source.name)
        assertEquals(IncomeSourceKind.OTHER, source.kind)
    }

    @Test fun blankNamesAreRejectedBeforeRepositorySave() {
        assertThrows(IllegalArgumentException::class.java) {
            IncomeSourceFactory.create("operation", " \t\n ", IncomeSourceKind.FREELANCE)
        }
    }

    @Test fun retryUsesSameFrozenContentAndOperationIdentity() {
        val first = IncomeSourceFactory.create("retained-operation", "Freelance", IncomeSourceKind.FREELANCE)
        val retry = IncomeSourceFactory.create("retained-operation", "Freelance", IncomeSourceKind.FREELANCE)

        assertEquals(first, retry)
        assertEquals(first.id, retry.id)
    }

    @Test fun busyAndAcceptedStatesPreventDuplicateSourceSubmission() {
        assertFalse(IncomeSourceManagementUiState(formOpen = true, busy = true).canStartSave())
        assertFalse(IncomeSourceManagementUiState(formOpen = true, saved = true).canStartSave())
        assertFalse(IncomeSourceManagementUiState(formOpen = false).canStartSave())
        assertTrue(IncomeSourceManagementUiState(formOpen = true).canStartSave())
    }

    @Test fun uncertainSourceRetryParticipatesInSessionIsolationWarning() {
        assertTrue(
            ManualIncomeUiState(
                sourceManagement = IncomeSourceManagementUiState(locked = true),
            ).hasUnresolvedSourceWork,
        )
        assertFalse(
            ManualIncomeUiState(
                sourceManagement = IncomeSourceManagementUiState(locked = true, saved = true),
            ).hasUnresolvedSourceWork,
        )
    }
}
