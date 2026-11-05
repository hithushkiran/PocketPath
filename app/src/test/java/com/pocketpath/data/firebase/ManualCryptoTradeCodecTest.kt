package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.calculation.ManualCryptoTrade
import com.pocketpath.domain.calculation.manualCryptoAssets
import com.pocketpath.domain.calculation.planManualCryptoTrade
import com.pocketpath.domain.calculation.summarizeHomeLedger
import com.pocketpath.domain.model.CryptoPosition
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.money.CryptoQuantity
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ManualCryptoTradeCodecTest {
    private val asset = manualCryptoAssets.single { it.code == "ETH" }
    private val zone = ZoneId.of("Asia/Colombo")
    private val at = Instant.parse("2026-11-05T06:00:00.123Z")
    private val date = at.atZone(zone).toLocalDate()
    private val time = FinancialTime(at, date, zone)
    private val audit = Timestamp(at.epochSecond, at.nano)
    private val empty = CryptoPosition(
        "wallet-ETH", CryptoQuantity.parse("0", asset), known(0), "opening-wallet-ETH", 0,
    )
    private val buy = ManualCryptoTrade(
        "buy", TransactionKind.CRYPTO_BUY, empty.accountId, "bank", CryptoQuantity.parse("2", asset),
        EntryAmount(Money.lkr(10_000_000)), Money.lkr(100_000), time, at, 0,
    )
    private val sell = buy.copy(
        operationId = "sell", kind = TransactionKind.CRYPTO_SELL,
        quantity = CryptoQuantity.parse("0.5", asset), gross = EntryAmount(Money.lkr(3_000_000)),
        fee = Money.lkr(50_000), expectedVersion = 1,
    )

    @Test
    fun buyAndPartialSaleRoundTripAndReportGainSeparatelyFromIncome() {
        val bought = planManualCryptoTrade(buy, empty)
        val sold = planManualCryptoTrade(sell, bought.position)
        val buyEvent = FoundationCodec.event("buy", FoundationCodec.eventData(bought.event) + ("createdAt" to audit))
        val sellEvent = FoundationCodec.event("sell", FoundationCodec.eventData(sold.event) + ("createdAt" to audit))
        assertTrue(buy.matches(buyEvent))
        assertTrue(sell.matches(sellEvent))
        assertEquals(listOf("crypto-fees"), buyEvent.categoryIds)
        assertEquals(listOf("crypto-fees"), sellEvent.categoryIds)
        assertFalse(sell.copy(fee = Money.lkr(50_001)).matches(sellEvent))
        assertEquals(NativeAmount.Fiat(Money.lkr(-10_100_000)), buyEvent.movements[1].amount)
        assertEquals(NativeAmount.Fiat(Money.lkr(2_950_000)), sellEvent.movements[1].amount)
        assertEquals(known(2_525_000), sellEvent.cryptoTrade?.allocatedCost)
        assertEquals(known(425_000), sellEvent.cryptoTrade?.realizedPnl)
        val position = FoundationCodec.cryptoPosition(
            empty.accountId, FoundationCodec.cryptoPositionData(sold.position, at) + ("updatedAt" to audit),
        )
        assertEquals(sold.position, position)
        assertEquals(CryptoQuantity.parse("1.5", asset), position.quantity)
        assertEquals(known(7_575_000), position.cost)
        assertEquals(2L, position.version)
        val summary = summarizeHomeLedger(CompleteLedger(listOf(buyEvent, sellEvent)), date, zone)
        assertEquals(0L, summary.receivedMinor)
        assertEquals(150_000L, summary.netSpendingMinor)
        assertEquals(425_000L, requireNotNull(summary.realizedCrypto).knownLkrMinor)
    }

    @Test
    fun staleVersionAndOversellingAreRejectedBeforeWriting() {
        val held = planManualCryptoTrade(buy, empty).position
        assertThrows(IllegalArgumentException::class.java) {
            planManualCryptoTrade(sell.copy(expectedVersion = 0), held)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planManualCryptoTrade(sell.copy(quantity = CryptoQuantity.parse("2.00000001", asset)), held)
        }
    }

    private fun known(minor: Long) = KnownLkrAmount.Known(Money.lkr(minor))
}
