package com.pocketpath.domain.model

import com.pocketpath.domain.money.*
import com.pocketpath.domain.validation.TransactionValidator
import java.time.Instant

enum class TransactionKind {
    OPENING, INCOME, EXPENSE, TRANSFER, FX_CONVERSION, CRYPTO_BUY, CRYPTO_SELL,
    REFUND, REIMBURSEMENT, ADJUSTMENT, GOAL_RESERVE, GOAL_RELEASE, GOAL_PURCHASE,
}
enum class TransactionStatus { POSTED, VOIDED }
enum class ComponentNature { EARNING, SPENDING, REFUND, REIMBURSEMENT, REALIZED_CRYPTO, REALIZED_FX }

data class AccountMovement(val accountId: String, val amount: NativeAmount) {
    init { require(accountId.isNotBlank()) }
}

data class ReportComponent(
    val nature: ComponentNature,
    val lkrAmount: KnownLkrAmount,
    val categoryId: String? = null,
    val commitmentClass: CommitmentClass? = null,
    val incomeSourceId: String? = null,
    val originalAmount: NativeAmount? = null,
    val valuationRate: ValuationRate? = null,
    val rateSnapshotId: String? = null,
) {
    init {
        val analytical = nature in setOf(ComponentNature.REALIZED_CRYPTO, ComponentNature.REALIZED_FX)
        require(analytical || (lkrAmount is KnownLkrAmount.Known && lkrAmount.amount.minor >= 0))
        require(nature != ComponentNature.EARNING || !incomeSourceId.isNullOrBlank())
        require(nature != ComponentNature.SPENDING || (!categoryId.isNullOrBlank() && commitmentClass != null))
        require((originalAmount == null) == (valuationRate == null))
        require(rateSnapshotId == null || valuationRate != null)
        if (originalAmount != null && lkrAmount is KnownLkrAmount.Known) {
            require(historicalLkrValue(originalAmount, requireNotNull(valuationRate)) == lkrAmount.amount)
        }
    }
}

data class ReservationEffect(val goalId: String, val backingAccountId: String, val amountLkr: Money) {
    init {
        require(goalId.isNotBlank() && backingAccountId.isNotBlank())
        require(amountLkr.currency == FiatCurrency.LKR && amountLkr.minor != 0L)
    }
}

data class MovementValuation(
    val accountId: String, val nativeAmount: NativeAmount, val lkrAmount: Money,
    val rate: ValuationRate, val effectiveAt: Instant, val source: ValuationSource,
    val rateSnapshotId: String? = null,
) {
    init {
        require(accountId.isNotBlank())
        require(lkrAmount == historicalLkrValue(nativeAmount, rate))
    }
}

data class TradeFee(val accountId: String, val amount: NativeAmount, val lkrAmount: Money) {
    init {
        require(accountId.isNotBlank() && amount.atomicUnits().signum() > 0)
        require(lkrAmount.currency == FiatCurrency.LKR && lkrAmount.minor >= 0)
    }
}

data class CryptoTrade(
    val assetAccountId: String, val quoteAccountId: String, val quantity: CryptoQuantity,
    val grossQuoteAmount: NativeAmount, val fees: List<TradeFee>,
    val proceedsOrAcquisitionLkr: Money,
    val allocatedCost: KnownLkrAmount? = null, val realizedPnl: KnownLkrAmount? = null,
    val costMethod: CostMethod = CostMethod.MOVING_AVERAGE_V1,
) {
    init {
        require(assetAccountId.isNotBlank() && quoteAccountId.isNotBlank() && assetAccountId != quoteAccountId)
        require(quantity.atomic.signum() > 0 && fees.size <= 4)
        require(grossQuoteAmount.atomicUnits().signum() > 0 && grossQuoteAmount.asset != quantity.asset)
        require(proceedsOrAcquisitionLkr.currency == FiatCurrency.LKR && proceedsOrAcquisitionLkr.minor >= 0)
        require(allocatedCost !is KnownLkrAmount.Known || allocatedCost.amount.minor >= 0)
        require(allocatedCost !is KnownLkrAmount.Unknown || realizedPnl is KnownLkrAmount.Unknown)
    }
}

data class VoidMetadata(val voidedAt: Instant, val voidReason: String) {
    init { require(voidReason.isNotBlank() && voidReason.length <= 1000) }
}

/**
 * One immutable event, not a draft. Derived query IDs cannot be supplied independently.
 * Collections are copied on construction. Structural validation does not resolve owned references,
 * funding, refund caps, occurrence uniqueness or position versions: repositories must enforce those.
 */
class FinancialTransaction(
    val operationId: String,
    val kind: TransactionKind,
    val status: TransactionStatus,
    val financialTime: FinancialTime,
    val clientCreatedAt: Instant,
    movements: List<AccountMovement>,
    components: List<ReportComponent> = emptyList(),
    reservationEffects: List<ReservationEffect> = emptyList(),
    valuations: List<MovementValuation> = emptyList(),
    val occurrenceId: String? = null,
    val expectedPaymentId: String? = null,
    val originalTransactionId: String? = null,
    val goalPlanId: String? = null,
    val note: String? = null,
    val merchantLabel: String? = null,
    cryptoTrade: CryptoTrade? = null,
    val replacesTransactionId: String? = null,
    val voidMetadata: VoidMetadata? = null,
    val createdAt: Instant? = null,
    val schemaVersion: Int = 1,
    val revision: Long = 0,
) {
    val id: String get() = operationId
    val movements: List<AccountMovement> = java.util.Collections.unmodifiableList(movements.toList())
    val components: List<ReportComponent> = java.util.Collections.unmodifiableList(components.toList())
    val reservationEffects: List<ReservationEffect> = java.util.Collections.unmodifiableList(reservationEffects.toList())
    val valuations: List<MovementValuation> = java.util.Collections.unmodifiableList(valuations.toList())
    val cryptoTrade: CryptoTrade? = cryptoTrade?.copy(fees = java.util.Collections.unmodifiableList(cryptoTrade.fees.toList()))
    val accountIds: List<String> get() = (movements.map { it.accountId } + reservationEffects.map { it.backingAccountId }).distinct().sorted()
    val categoryIds: List<String> get() = components.mapNotNull { it.categoryId }.distinct().sorted()
    val goalIds: List<String> get() = reservationEffects.map { it.goalId }.distinct().sorted()
    val incomeSourceIds: List<String> get() = components.mapNotNull { it.incomeSourceId }.distinct().sorted()
    init {
        val errors = TransactionValidator.errors(this)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
