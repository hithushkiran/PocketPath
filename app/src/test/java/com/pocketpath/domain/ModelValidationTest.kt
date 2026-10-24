package com.pocketpath.domain

import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ModelValidationTest {
    private val time = SampleFixtures.time
    private fun movement(minor: Long) = AccountMovement("cash", NativeAmount.Fiat(Money.lkr(minor)))
    private fun spending(minor: Long) = ReportComponent(ComponentNature.SPENDING,
        KnownLkrAmount.Known(Money.lkr(minor)), "food", CommitmentClass.DISCRETIONARY)

    @Test fun cryptoQuoteAssetAndItsScaleRemainNativeWithoutAssumedUsdParity() {
        val eth = CryptoQuantity.parse("0.5", CryptoAsset("ETH", 18))
        val usdt = NativeAmount.Crypto(CryptoQuantity.parse("1000", CryptoAsset("USDT", 6)))
        val trade = CryptoTrade("eth", "usdt", eth, usdt, emptyList(), Money.lkr(30000000))
        val event = FinancialTransaction("buy", TransactionKind.CRYPTO_BUY, TransactionStatus.POSTED,
            time, time.occurredAt, listOf(AccountMovement("eth", NativeAmount.Crypto(eth)),
                AccountMovement("usdt", NativeAmount.Crypto(CryptoQuantity(usdt.quantity.asset, -usdt.quantity.atomic)))), cryptoTrade = trade)
        assertEquals("USDT", event.cryptoTrade!!.grossQuoteAmount.asset.code)
        assertEquals(6, event.cryptoTrade.grossQuoteAmount.asset.scale)
        assertTrue(event.components.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad-buy-profit", TransactionKind.CRYPTO_BUY, TransactionStatus.POSTED,
                time, time.occurredAt, event.movements, listOf(ReportComponent(ComponentNature.REALIZED_CRYPTO,
                    KnownLkrAmount.Known(Money.lkr(0)))), cryptoTrade = trade)
        }
    }

    @Test fun effectiveDueDateChangesDoNotChangeOccurrenceOrReceiptIdentity() {
        val occurrence = Occurrence("salary_monthly", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25),
            ScheduleKind.INCOME_REMINDER, "Salary", EntryAmount(Money.lkr(10000)), time.recordedTimeZone, sourceId = "salary")
        assertEquals("salary_monthly_2026-10-25", occurrence.id)
        val moved = occurrence.copy(effectiveDueDate = LocalDate.of(2026, 10, 28))
        assertEquals(occurrence.id, moved.id)
        assertEquals("receipt_salary_monthly_2026-10-25", StableIds.scheduledReceipt(moved.id))
        assertEquals("goal_2026-10-01", StableIds.goalPlan("goal", LocalDate.of(2026, 10, 1)))
    }

    @Test fun incompatibleScheduleAndOccurrenceReferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Occurrence("salary", time.occurredLocalDate, time.occurredLocalDate, ScheduleKind.INCOME_REMINDER,
                "Salary", EntryAmount(Money.lkr(1)), time.recordedTimeZone, sourceId = "source", categoryId = "category")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Occurrence("bill", time.occurredLocalDate, time.occurredLocalDate, ScheduleKind.COMMITMENT,
                "Bill", EntryAmount(Money.lkr(1)), time.recordedTimeZone, categoryId = "category",
                commitmentClass = CommitmentClass.COMMITTED, state = OccurrenceState.CONFIRMED, confirmedTransactionId = "event")
        }
    }

    @Test fun envelopeBoundsAndKindCompatibilityAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad", TransactionKind.EXPENSE, TransactionStatus.POSTED, time, time.occurredAt,
                List(5) { movement(-1) }, listOf(spending(5)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad", TransactionKind.OPENING, TransactionStatus.POSTED, time, time.occurredAt,
                listOf(movement(10)), listOf(ReportComponent(ComponentNature.EARNING, KnownLkrAmount.Known(Money.lkr(10)), incomeSourceId = "source")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad", TransactionKind.EXPENSE, TransactionStatus.POSTED, time, time.occurredAt,
                listOf(movement(-100)), listOf(spending(50)))
        }
    }

    @Test fun inconsistentTransferFeeAndMissingRefundLinkAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad", TransactionKind.TRANSFER, TransactionStatus.POSTED, time, time.occurredAt,
                listOf(movement(-5100), AccountMovement("other", NativeAmount.Fiat(Money.lkr(5000)))), listOf(spending(200)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinancialTransaction("bad", TransactionKind.REFUND, TransactionStatus.POSTED, time, time.occurredAt,
                listOf(movement(100)), listOf(ReportComponent(ComponentNature.REFUND, KnownLkrAmount.Known(Money.lkr(100)))))
        }
    }

    @Test fun queryReferencesAreDerivedAndCollectionsDoNotAliasCallerLists() {
        val movements = mutableListOf(movement(-100))
        val event = FinancialTransaction("expense", TransactionKind.EXPENSE, TransactionStatus.POSTED,
            time, time.occurredAt, movements, listOf(spending(100)))
        movements.clear()
        assertEquals(listOf("cash"), event.accountIds)
        assertEquals(listOf("food"), event.categoryIds)
        assertEquals(1, event.movements.size)
        assertThrows(UnsupportedOperationException::class.java) { (event.movements as MutableList).clear() }
    }
}
