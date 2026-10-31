package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.CryptoPosition
import com.pocketpath.domain.money.CryptoQuantity
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * BR-12 moving-average cost-basis calculations.
 *
 * This is deliberately a pure calculation. A protected repository must serialize the returned
 * position with the immutable buy/sell event using the position version supplied by its caller.
 */
object CryptoCostBasisCalculations {
    data class Disposal(
        val nextPosition: CryptoPosition,
        val allocatedCost: KnownLkrAmount,
        val realizedPnl: KnownLkrAmount,
        val netProceedsLkr: Money,
    )

    fun acquire(
        position: CryptoPosition,
        transactionId: String,
        quantity: CryptoQuantity,
        acquisitionCostLkr: Money,
    ): CryptoPosition {
        require(transactionId.isNotBlank() && '/' !in transactionId)
        require(quantity.asset == position.quantity.asset && quantity.atomic.signum() > 0)
        require(acquisitionCostLkr.currency == FiatCurrency.LKR && acquisitionCostLkr.minor >= 0)
        val nextQuantity = position.quantity + quantity
        val nextCost = when (val cost = position.cost) {
            is KnownLkrAmount.Known -> KnownLkrAmount.Known(cost.amount + acquisitionCostLkr)
            is KnownLkrAmount.Unknown -> cost
        }
        return position.copy(
            quantity = nextQuantity,
            cost = nextCost,
            lastTransactionId = transactionId,
            version = Math.addExact(position.version, 1L),
        )
    }

    fun dispose(
        position: CryptoPosition,
        transactionId: String,
        quantity: CryptoQuantity,
        grossProceedsLkr: Money,
        disposalFeesLkr: List<Money>,
    ): Disposal {
        require(transactionId.isNotBlank() && '/' !in transactionId)
        require(quantity.asset == position.quantity.asset && quantity.atomic.signum() > 0)
        require(quantity.atomic <= position.quantity.atomic) { "Cannot sell more than the recorded position." }
        require(grossProceedsLkr.currency == FiatCurrency.LKR && grossProceedsLkr.minor >= 0)
        require(disposalFeesLkr.all { it.currency == FiatCurrency.LKR && it.minor >= 0 })

        val fees = disposalFeesLkr.fold(Money.lkr(0L)) { total, fee -> total + fee }
        val netProceeds = grossProceedsLkr + -fees
        val remainingQuantity = CryptoQuantity(position.quantity.asset, position.quantity.atomic - quantity.atomic)
        val result = when (val cost = position.cost) {
            is KnownLkrAmount.Known -> {
                // A full disposal takes the complete residual cost, avoiding cumulative rounding drift.
                val allocated = if (remainingQuantity.atomic.signum() == 0) {
                    cost.amount
                } else {
                    Money.lkr(
                        BigDecimal.valueOf(cost.amount.minor)
                            .multiply(BigDecimal(quantity.atomic))
                            .divide(BigDecimal(position.quantity.atomic), 0, RoundingMode.HALF_UP)
                            .longValueExact(),
                    )
                }
                val remainingCost = cost.amount + -allocated
                val pnl = netProceeds + -allocated
                Triple(KnownLkrAmount.Known(allocated), KnownLkrAmount.Known(pnl), KnownLkrAmount.Known(remainingCost))
            }
            is KnownLkrAmount.Unknown -> Triple(cost, cost, cost)
        }
        return Disposal(
            nextPosition = position.copy(
                quantity = remainingQuantity,
                cost = result.third,
                lastTransactionId = transactionId,
                version = Math.addExact(position.version, 1L),
            ),
            allocatedCost = result.first,
            realizedPnl = result.second,
            netProceedsLkr = netProceeds,
        )
    }
}
