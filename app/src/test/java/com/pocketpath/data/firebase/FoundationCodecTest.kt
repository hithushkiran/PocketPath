package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.data.local.LocalFinanceFile
import com.pocketpath.data.local.SavedDrafts
import com.pocketpath.data.local.StoredExpense
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.repository.RecordExpenseCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.calculation.BalanceUnits
import com.pocketpath.domain.calculation.ReportPeriod
import com.pocketpath.domain.calculation.accountBalance
import com.pocketpath.domain.calculation.periodReportTotals
import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FoundationCodecTest {
    private val audit = Timestamp(1_791_000_000L, 0)

    @Test fun decodedOpeningAndExpenseGive9150WithoutOpeningIncome() {
        val account = FoundationCodec.account(
            SampleFixtures.cash.id,
            FoundationCodec.accountData(SampleFixtures.cash) + mapOf("createdAt" to audit, "updatedAt" to audit),
        )
        val opening = event(SampleFixtures.cash.openingTransactionId, TransactionKind.OPENING, 1_000_000L)
        val expense = event("stored-expense", TransactionKind.EXPENSE, -85_000L)
        val ledger = CompleteLedger(listOf(opening, expense).map {
            FoundationCodec.event(it.id, stored(it))
        })

        assertEquals(915_000L, (accountBalance(account, ledger).units as BalanceUnits.Fiat).minor)
        val totals = periodReportTotals(ledger, ReportPeriod(
            SampleFixtures.time.occurredLocalDate,
            SampleFixtures.time.occurredLocalDate,
            SampleFixtures.time.recordedTimeZone,
        ))
        assertEquals(0L, totals.earningsLkrMinor)
        assertEquals(85_000L, totals.netSpendingLkrMinor)
    }

    @Test fun replayIgnoresAuditTimesAfterTimestampNormalizationButRejectsChangedAmounts() {
        val firstTime = Instant.parse("2026-10-01T06:00:00.123456001Z")
        val retryTime = Instant.parse("2026-10-01T06:00:00.123456999Z")
        val first = stored(event("opening", TransactionKind.OPENING, 1_000_000L, firstTime))
        val retry = stored(event("opening", TransactionKind.OPENING, 1_000_000L, retryTime)) +
            ("createdAt" to Timestamp(audit.seconds + 60L, 0))

        assertEquals(123_456_000, (first.getValue("occurredAt") as Timestamp).nanoseconds)
        assertEquals(first.getValue("occurredAt"), retry.getValue("occurredAt"))
        assertEquals(first.getValue("clientCreatedAt"), retry.getValue("clientCreatedAt"))
        assertTrue(FoundationCodec.sameEvent(first, retry))
        val changed = stored(event("opening", TransactionKind.OPENING, 1_000_001L, retryTime))
        assertFalse(FoundationCodec.sameEvent(first, changed))

        val account = FoundationCodec.accountData(SampleFixtures.cash) + mapOf("createdAt" to audit, "updatedAt" to audit)
        val accountRetry = account + mapOf(
            "createdAt" to Timestamp(audit.seconds + 60L, 0),
            "updatedAt" to Timestamp(audit.seconds + 120L, 0),
        )
        assertTrue(FoundationCodec.sameAccount(account, accountRetry))
    }

    @Test fun floatingPointMoneyAndInconsistentQueryArraysAreRejected() {
        val expense = event("stored-expense", TransactionKind.EXPENSE, -85_000L)
        val payload = stored(expense)
        val movement = (payload.getValue("movements") as List<*>).single() as Map<*, *>
        val component = (payload.getValue("components") as List<*>).single() as Map<*, *>

        listOf(-85_000.0, -85_000.0f).forEach { floating ->
            val malformed = payload + ("movements" to listOf(movement + ("amountMinor" to floating)))
            assertThrows(IllegalArgumentException::class.java) { FoundationCodec.event(expense.id, malformed) }
        }
        listOf(85_000.0, 85_000.0f).forEach { floating ->
            val malformed = payload + ("components" to listOf(component + ("amountLkrMinor" to floating)))
            assertThrows(IllegalArgumentException::class.java) { FoundationCodec.event(expense.id, malformed) }
        }
        listOf("accountIds", "categoryIds", "goalIds", "incomeSourceIds").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                FoundationCodec.event(expense.id, payload + (field to listOf("inconsistent-reference")))
            }
        }
    }

    @Test fun pendingEventAllowsUnresolvedServerTimestamp() {
        val expense = event("pending-expense", TransactionKind.EXPENSE, -85_000L)
        val payload = stored(expense) + ("createdAt" to null)

        val decoded = FoundationCodec.event(expense.id, payload, pending = true)

        assertNull(decoded.createdAt)
        assertEquals(expense.operationId, decoded.operationId)
        assertEquals(expense.movements, decoded.movements)
    }

    @Test fun confirmedEventRequiresResolvedServerTimestamp() {
        val expense = event("confirmed-expense", TransactionKind.EXPENSE, -85_000L)
        val payload = stored(expense)

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(expense.id, payload + ("createdAt" to null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(expense.id, payload - "createdAt")
        }
        assertEquals(audit.toDate().toInstant(), FoundationCodec.event(expense.id, payload).createdAt)
    }

    @Test fun persistedExpenseRoundTripPreservesScopeIdentityAndExactAmount() {
        val command = RecordExpenseCommand(
            operationId = "retained-operation",
            accountId = SampleFixtures.cash.id,
            amount = EntryAmount(Money.lkr(85_001L)),
            categoryId = SampleFixtures.food.id,
            commitmentClass = SampleFixtures.food.defaultCommitmentClass,
            financialTime = SampleFixtures.time,
            clientCreatedAt = SampleFixtures.instant,
            note = "Retained draft",
        )
        val original = LocalFinanceFile(
            userId = "sample-user-a",
            draftRevision = 12L,
            drafts = SavedDrafts(expenseCommand = StoredExpense.from(command)),
        )
        val json = Json { encodeDefaults = true }
        val file = Files.createTempFile("pocketpath-expense-", ".json")
        try {
            Files.write(file, json.encodeToString(original).toByteArray(Charsets.UTF_8))
            val restored = json.decodeFromString<LocalFinanceFile>(
                String(Files.readAllBytes(file), Charsets.UTF_8),
            )
            val restoredCommand = requireNotNull(restored.drafts.expenseCommand).command()

            assertEquals(original, restored)
            assertEquals("sample-user-a", restored.userId)
            assertNotEquals("sample-user-b", restored.userId)
            assertEquals(command.operationId, restoredCommand.operationId)
            assertEquals(85_001L, restoredCommand.amount.money.minor)
            assertEquals(command.amount, restoredCommand.amount)
            assertEquals(command.accountId, restoredCommand.accountId)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun stored(event: FinancialTransaction): Map<String, Any> =
        FoundationCodec.eventData(event) + ("createdAt" to audit)

    private fun event(
        id: String,
        kind: TransactionKind,
        minor: Long,
        instant: Instant = SampleFixtures.instant,
    ): FinancialTransaction = FinancialTransaction(
        operationId = id,
        kind = kind,
        status = TransactionStatus.POSTED,
        financialTime = SampleFixtures.time.copy(occurredAt = instant),
        clientCreatedAt = instant,
        movements = listOf(AccountMovement(SampleFixtures.cash.id, NativeAmount.Fiat(Money.lkr(minor)))),
        components = if (kind == TransactionKind.EXPENSE) listOf(ReportComponent(
            nature = ComponentNature.SPENDING,
            lkrAmount = KnownLkrAmount.Known(Money.lkr(-minor)),
            categoryId = SampleFixtures.food.id,
            commitmentClass = SampleFixtures.food.defaultCommitmentClass,
        )) else emptyList(),
    )
}
