package com.pocketpath.feature.income

import com.google.firebase.Timestamp
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.domain.calculation.summarizeHomeLedger
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.AccountType
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.model.IncomeSourceKind
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CompleteLedger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun convertedReceiptRoundTripsExactOriginalRateAndSingleRoundedLkrCredit() {
        val event = adSenseIncome("300.5")
        val component = event.components.single()
        assertEquals(NativeAmount.Fiat(Money(FiatCurrency.USD, 1L)), component.originalAmount)
        assertEquals(301L, event.movements.single().amount.atomicUnits().toLong())
        assertEquals(KnownLkrAmount.Known(Money.lkr(301L)), component.lkrAmount)

        val payload = FoundationCodec.eventData(event)
        val stored = payload + ("createdAt" to Timestamp(now.epochSecond, 0))
        val reloaded = FoundationCodec.event(event.id, stored)
        assertEquals(component, reloaded.components.single())
        assertEquals(event.movements, reloaded.movements)
        assertTrue(FoundationCodec.sameEvent(stored, payload))
        // Same rounded LKR, different historical rate is still a different operation.
        assertFalse(FoundationCodec.sameEvent(stored, FoundationCodec.eventData(adSenseIncome("300.6"))))
    }

    @Test fun homeGroupsActualReceiptsBySourceWithinTheMonth() {
        val salary = income("100", "2026-10-24")
        val olderSalary = income("50", "2026-09-30", operationId = "last-month")
        val summary = summarizeHomeLedger(
            CompleteLedger(listOf(salary, olderSalary, adSenseIncome("300.5"))),
            LocalDate.of(2026, 10, 25),
            zone,
        )
        assertEquals(mapOf("salary" to 10_000L, "adsense" to 301L), summary.receivedBySourceMinor)
        assertEquals(10_301L, summary.receivedMinor)
    }

    @Test fun rejectsInvalidConvertedUsdAmountsAndRates() {
        for (rate in listOf("0", "-1", "300.0000001", "10000", "3e2", "0.000001")) {
            assertThrows(IllegalArgumentException::class.java) { adSenseIncome(rate) }
        }
        assertThrows(IllegalArgumentException::class.java) { adSenseIncome("300", amount = "0.001") }
        assertThrows(IllegalArgumentException::class.java) {
            ManualIncomeEventFactory.create(
                "id", source.id, account.id, "1", "2026-10-24", "", zone, now,
                listOf(account), listOf(source), usdRateText = "300",
            )
        }
    }

    private fun adSenseIncome(rate: String, amount: String = "0.01"): com.pocketpath.domain.model.FinancialTransaction {
        val source = IncomeSource("adsense", "AdSense", IncomeSourceKind.ADSENSE)
        return ManualIncomeEventFactory.create(
            "converted-receipt", source.id, account.id, amount, "2026-10-24", "", zone, now,
            listOf(account), listOf(source), usdRateText = rate,
        )
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
