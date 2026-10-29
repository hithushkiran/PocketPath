package com.pocketpath.domain

import com.pocketpath.domain.calculation.CryptoCostBasisCalculations
import com.pocketpath.domain.model.CryptoPosition
import com.pocketpath.domain.money.CryptoAsset
import com.pocketpath.domain.money.CryptoQuantity
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoCostBasisCalculationsTest {
    private val eth = CryptoAsset("ETH", 18)
    private fun position(quantity: String = "2", cost: KnownLkrAmount = KnownLkrAmount.Known(Money.lkr(2_000_000L))) = CryptoPosition(
        accountId = "eth_wallet", quantity = CryptoQuantity.parse(quantity, eth), cost = cost,
        lastTransactionId = "opening", version = 0L,
    )

    @Test fun partialDisposalUsesHalfUpMovingAverageAndDeductsFeeOnce() {
        val disposal = CryptoCostBasisCalculations.dispose(
            position(), "sell_1", CryptoQuantity.parse("0.5", eth), Money.lkr(700_000L), listOf(Money.lkr(20_000L)),
        )
        assertEquals(500_000L, (disposal.allocatedCost as KnownLkrAmount.Known).amount.minor)
        assertEquals(180_000L, (disposal.realizedPnl as KnownLkrAmount.Known).amount.minor)
        assertEquals(1_500_000L, (disposal.nextPosition.cost as KnownLkrAmount.Known).amount.minor)
        assertEquals(CryptoQuantity.parse("1.5", eth), disposal.nextPosition.quantity)
        assertEquals(1L, disposal.nextPosition.version)
    }

    @Test fun fullDisposalClearsResidualCostAfterPriorRounding() {
        val first = CryptoCostBasisCalculations.dispose(
            position("3", KnownLkrAmount.Known(Money.lkr(100L))), "sell_1", CryptoQuantity.parse("1", eth), Money.lkr(50L), emptyList(),
        )
        val final = CryptoCostBasisCalculations.dispose(
            first.nextPosition, "sell_2", CryptoQuantity.parse("2", eth), Money.lkr(100L), emptyList(),
        )
        assertEquals(33L, (first.allocatedCost as KnownLkrAmount.Known).amount.minor)
        assertEquals(67L, (final.allocatedCost as KnownLkrAmount.Known).amount.minor)
        assertEquals(0L, (final.nextPosition.cost as KnownLkrAmount.Known).amount.minor)
        assertEquals(BigInteger.ZERO, final.nextPosition.quantity.atomic)
    }

    @Test fun unknownOpeningCostPreservesUnknownProfit() {
        val unknown = KnownLkrAmount.Unknown("Opening cost unavailable")
        val disposal = CryptoCostBasisCalculations.dispose(
            position(cost = unknown), "sell_1", CryptoQuantity.parse("0.5", eth), Money.lkr(700_000L), emptyList(),
        )
        assertEquals(unknown, disposal.allocatedCost)
        assertEquals(unknown, disposal.realizedPnl)
        assertEquals(unknown, disposal.nextPosition.cost)
    }

    @Test fun rejectsOversellWrongAssetAndInvalidFee() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CryptoCostBasisCalculations.dispose(position(), "sell", CryptoQuantity.parse("2.01", eth), Money.lkr(1), emptyList())
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CryptoCostBasisCalculations.dispose(position(), "sell", CryptoQuantity.parse("1", eth), Money.lkr(1), listOf(Money.lkr(-1)))
        }
        assertTrue(CryptoCostBasisCalculations.acquire(
            position(), "buy", CryptoQuantity.parse("0.1", eth), Money.lkr(1),
        ).quantity.atomic > position().quantity.atomic)
    }
}
