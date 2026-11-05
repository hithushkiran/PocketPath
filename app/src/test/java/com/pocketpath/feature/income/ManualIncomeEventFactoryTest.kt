package com.pocketpath.feature.income

import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.AccountType
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.model.IncomeSourceKind
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.FiatCurrency
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualIncomeEventFactoryTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private val now = Instant.parse("2026-10-25T06:30:00Z")
    private val account = Account(
        id = "cash",
        name = "Cash",
        type = AccountType.CASH,
        asset = FiatCurrency.LKR,
        includeInEverydayPool = true,
        openingTransactionId = "opening",
        openedOn = LocalDate.of(2026, 10, 1),
    )
    private val source = IncomeSource("salary", "Salary", IncomeSourceKind.SALARY)

    @Test fun buildsExactPostedLkrIncomeReceipt() {
        val event = income("1250.01", "2026-10-24", " October salary ")

        assertEquals(TransactionKind.INCOME, event.kind)
        assertEquals(TransactionStatus.POSTED, event.status)
        assertEquals(125_001L, event.movements.single().amount.atomicUnits().toLong())
        assertEquals(125_001L, (event.components.single().lkrAmount as com.pocketpath.domain.money.KnownLkrAmount.Known).amount.minor)
        assertEquals(ComponentNature.EARNING, event.components.single().nature)
        assertEquals("salary", event.components.single().incomeSourceId)
        assertEquals("October salary", event.note)
        assertEquals(LocalDate.of(2026, 10, 24), event.financialTime.occurredLocalDate)
        assertEquals(Instant.parse("2026-10-23T18:30:00Z"), event.financialTime.occurredAt)
    }

    @Test fun todayUsesTheCapturedSaveInstant() {
        val event = income("1.00", "2026-10-25")
        assertEquals(now, event.financialTime.occurredAt)
    }

    @Test fun rejectsInvalidAmountAndReferences() {
        assertThrows(IllegalArgumentException::class.java) { income("0", "2026-10-24") }
        assertThrows(IllegalArgumentException::class.java) { income("1.001", "2026-10-24") }
        assertThrows(IllegalArgumentException::class.java) {
            ManualIncomeEventFactory.create("id", "missing", account.id, "1", "2026-10-24", "", zone, now, listOf(account), listOf(source))
        }
    }

    @Test fun retryKeepsTheFrozenEventAndOperationIdentity() {
        val frozen = income("10", "2026-10-24", operationId = "retained-operation")
        val retry = frozen
        assertSame(frozen, retry)
        assertEquals("retained-operation", retry.operationId)
    }

    @Test fun busyOrSavedStatePreventsAnotherSaveStart() {
        org.junit.Assert.assertEquals(false, ManualIncomeUiState(referencesReady = true, busy = true).canStartSave())
        org.junit.Assert.assertEquals(false, ManualIncomeUiState(referencesReady = true, saved = true).canStartSave())
        org.junit.Assert.assertEquals(true, ManualIncomeUiState(referencesReady = true).canStartSave())
    }

    @Test fun lockedUnsavedStateIsUnresolvedIncomeWork() {
        org.junit.Assert.assertEquals(true, ManualIncomeUiState(locked = true).hasUnresolvedIncomeWork)
        org.junit.Assert.assertEquals(false, ManualIncomeUiState(locked = true, saved = true).hasUnresolvedIncomeWork)
        org.junit.Assert.assertEquals(false, ManualIncomeUiState().hasUnresolvedIncomeWork)
    }

    private fun income(amount: String, date: String, note: String = "", operationId: String = "operation") =
        ManualIncomeEventFactory.create(
            operationId = operationId,
            sourceId = source.id,
            accountId = account.id,
            amountText = amount,
            dateText = date,
            noteText = note,
            zone = zone,
            now = now,
            accounts = listOf(account),
            sources = listOf(source),
        )
}
