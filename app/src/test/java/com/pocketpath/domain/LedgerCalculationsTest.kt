package com.pocketpath.domain

import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

class LedgerCalculationsTest {
    private val time = SampleFixtures.time
    private val period = ReportPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), time.recordedTimeZone)
    private val cash = SampleFixtures.cash
    private fun movement(account: String, minor: Long) = AccountMovement(account, NativeAmount.Fiat(Money.lkr(minor)))
    private fun known(minor: Long) = KnownLkrAmount.Known(Money.lkr(minor))
    private fun spending(minor: Long) = ReportComponent(ComponentNature.SPENDING, known(minor), "food", CommitmentClass.DISCRETIONARY)
    private fun opening(account: String = cash.id, minor: Long = 1_000_000) = FinancialTransaction(
        "opening-$account", TransactionKind.OPENING, TransactionStatus.POSTED, time, time.occurredAt, listOf(movement(account, minor)))
    private fun expense(status: TransactionStatus = TransactionStatus.POSTED) = FinancialTransaction(
        "expense", TransactionKind.EXPENSE, status, time, time.occurredAt,
        listOf(movement(cash.id, -85000)), listOf(spending(85000)),
        voidMetadata = if (status == TransactionStatus.VOIDED) VoidMetadata(time.occurredAt, "Corrected") else null)

    @Test fun openingMinusExpenseIs9150AndOpeningIsNotIncome() {
        val ledger = CompleteLedger(listOf(opening(), expense()))
        assertEquals(915000L, (accountBalance(cash, ledger).units as BalanceUnits.Fiat).minor)
        val report = periodReportTotals(ledger, period)
        assertEquals(0L, report.earningsLkrMinor)
        assertEquals(85000L, report.netSpendingLkrMinor)
    }

    @Test fun transferPrincipalCancelsAndFeeOccursOnce() {
        val bank = cash.copy(id = "bank", openingTransactionId = "bank-opening")
        val transfer = FinancialTransaction("transfer", TransactionKind.TRANSFER, TransactionStatus.POSTED,
            time, time.occurredAt, listOf(movement(bank.id, -500000), movement(cash.id, 500000), movement(bank.id, -10000)),
            listOf(spending(10000)))
        val ledger = CompleteLedger(listOf(opening(bank.id), opening(minor = 0), transfer))
        val bankBalance = (accountBalance(bank, ledger).units as BalanceUnits.Fiat).minor
        val cashBalance = (accountBalance(cash, ledger).units as BalanceUnits.Fiat).minor
        assertEquals(990000L, bankBalance + cashBalance)
        assertEquals(0L, periodReportTotals(ledger, period).earningsLkrMinor)
        assertEquals(10000L, periodReportTotals(ledger, period).grossSpendingLkrMinor)
    }

    @Test fun voidedEventsAreRetainedButExcluded() {
        val ledger = CompleteLedger(listOf(opening(), expense(TransactionStatus.VOIDED)))
        assertEquals(1_000_000L, (accountBalance(cash, ledger).units as BalanceUnits.Fiat).minor)
        assertEquals(0L, periodReportTotals(ledger, period).netSpendingLkrMinor)
    }

    @Test fun reservationChangesEarmarksWithoutChangingNativeBalance() {
        val reserve = FinancialTransaction("reserve", TransactionKind.GOAL_RESERVE, TransactionStatus.POSTED,
            time, time.occurredAt, emptyList(), reservationEffects = listOf(ReservationEffect("goal", cash.id, Money.lkr(200000))))
        val ledger = CompleteLedger(listOf(opening(), reserve))
        assertEquals(200000L, goalEarmarkLkrMinor(ledger, "goal"))
        assertEquals(1_000_000L, (accountBalance(cash, ledger).units as BalanceUnits.Fiat).minor)
        assertEquals(listOf(cash.id), reserve.accountIds)
        assertEquals(0L, periodReportTotals(ledger, period).grossSpendingLkrMinor)
    }

    @Test fun usdQuantityIsDistinctFromHistoricalLkrIncomeAndEverydayFunds() {
        val usd = cash.copy(id = "usd", asset = FiatCurrency.USD, includeInEverydayPool = false, openingTransactionId = "usd-opening")
        val native = NativeAmount.Fiat(Money.parse("100", FiatCurrency.USD))
        val rate = ValuationRate(BigDecimal("300"))
        val lkr = historicalLkrValue(native, rate)
        val receipt = FinancialTransaction("usd-income", TransactionKind.INCOME, TransactionStatus.POSTED,
            time, time.occurredAt, listOf(AccountMovement(usd.id, native)),
            listOf(ReportComponent(ComponentNature.EARNING, KnownLkrAmount.Known(lkr), incomeSourceId = "adsense",
                originalAmount = native, valuationRate = rate)),
            valuations = listOf(MovementValuation(usd.id, native, lkr, rate, time.occurredAt, ValuationSource.MANUAL_ACTUAL)),
            createdAt = Instant.parse("2026-11-01T00:00:00Z"))
        val ledger = CompleteLedger(listOf(opening(), receipt))
        assertEquals(10000L, (accountBalance(usd, ledger).units as BalanceUnits.Fiat).minor)
        assertEquals(1_000_000L, (accountBalance(cash, ledger).units as BalanceUnits.Fiat).minor)
        assertEquals(3_000_000L, periodReportTotals(ledger, period).earningsLkrMinor)
        assertThrows(IllegalArgumentException::class.java) { usd.copy(includeInEverydayPool = true) }
    }

    @Test fun unknownCryptoCostAndProfitRemainUnknownInReport() {
        val eth = CryptoAsset("ETH", 18)
        val quantity = CryptoQuantity.parse("0.5", eth)
        val unknown = KnownLkrAmount.Unknown("Opening cost unavailable")
        val trade = CryptoTrade("eth", cash.id, quantity, NativeAmount.Fiat(Money.lkr(700000)), emptyList(),
            Money.lkr(700000), unknown, unknown)
        val sale = FinancialTransaction("sale", TransactionKind.CRYPTO_SELL, TransactionStatus.POSTED, time, time.occurredAt,
            listOf(AccountMovement("eth", NativeAmount.Crypto(CryptoQuantity(eth, -quantity.atomic))), movement(cash.id, 700000)),
            listOf(ReportComponent(ComponentNature.REALIZED_CRYPTO, unknown)), cryptoTrade = trade)
        val total = periodReportTotals(CompleteLedger(listOf(sale)), period).realizedCrypto
        assertFalse(total.isComplete)
        assertEquals(listOf("Opening cost unavailable"), total.unknownReasons)
        assertThrows(IllegalArgumentException::class.java) { trade.copy(realizedPnl = known(0)) }
    }

    @Test fun negativeRecordedBalanceIsAllowedWithWarning() {
        val balance = accountBalance(cash, CompleteLedger(listOf(opening(minor = 0), expense())))
        assertEquals(-85000L, (balance.units as BalanceUnits.Fiat).minor)
        assertEquals(setOf(BalanceWarning.NEGATIVE_RECORDED_BALANCE), balance.warnings)
    }

    @Test fun duplicateIdentitiesAndAccountOnlyReportSourcesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CompleteLedger(listOf(opening(), opening())) }
        assertThrows(IllegalArgumentException::class.java) { periodReportTotals(CompleteLedger(listOf(opening()), LedgerScope.AccountEvents(cash.id)), period) }
        assertThrows(IllegalArgumentException::class.java) { accountBalance(cash.copy(asset = FiatCurrency.USD, includeInEverydayPool = false), CompleteLedger(listOf(opening()))) }
    }

    @Test fun balanceSumChecksLongOverflowWithoutApplyingPerEventCapToAggregate() {
        val c = FiatCurrency("XTS", 10)
        val account = cash.copy(asset = c, includeInEverydayPool = false)
        fun event(id: String, minor: Long) = FinancialTransaction(id, TransactionKind.ADJUSTMENT, TransactionStatus.POSTED,
            time, time.occurredAt, listOf(AccountMovement(cash.id, NativeAmount.Fiat(Money(c, minor)))))
        assertThrows(ArithmeticException::class.java) { accountBalance(account, CompleteLedger(listOf(event("a", Long.MAX_VALUE), event("b", 1)))) }
    }

    @Test fun refundsUseActualReceiptPeriodAndReimbursementsAreNotEarnings() {
        val novemberTime = FinancialTime(Instant.parse("2026-11-01T06:00:00Z"), LocalDate.of(2026, 11, 1), time.recordedTimeZone)
        val refund = FinancialTransaction("refund", TransactionKind.REFUND, TransactionStatus.POSTED,
            novemberTime, novemberTime.occurredAt, listOf(movement(cash.id, 10000)),
            listOf(ReportComponent(ComponentNature.REFUND, known(10000))), originalTransactionId = "expense")
        val reimbursement = FinancialTransaction("reimburse", TransactionKind.REIMBURSEMENT, TransactionStatus.POSTED,
            time, time.occurredAt, listOf(movement(cash.id, 5000)), listOf(ReportComponent(ComponentNature.REIMBURSEMENT, known(5000))))
        val ledger = CompleteLedger(listOf(expense(), refund, reimbursement))
        assertEquals(85000L, periodReportTotals(ledger, period).netSpendingLkrMinor)
        assertEquals(0L, periodReportTotals(ledger, period).earningsLkrMinor)
        assertEquals(5000L, periodReportTotals(ledger, period).reimbursementsLkrMinor)
        assertEquals(-10000L, periodReportTotals(ledger, ReportPeriod(novemberTime.occurredLocalDate,
            novemberTime.occurredLocalDate, time.recordedTimeZone)).netSpendingLkrMinor)
    }
}
