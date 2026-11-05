package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import java.math.BigInteger
import java.time.Instant

// The Firestore adapter uses a bounded subset of the domain's arbitrary-precision quantities.
val manualCryptoAssets = listOf(CryptoAsset("BTC", 8), CryptoAsset("ETH", 8), CryptoAsset("USDT", 6))
internal const val CRYPTO_FEE_CATEGORY_ID = "crypto-fees"
private val maximumManualAtoms = BigInteger("999999999999")

fun requireManualCryptoQuantity(quantity: CryptoQuantity) {
    require(quantity.asset in manualCryptoAssets)
    require(quantity.atomic.abs() <= maximumManualAtoms) {
        "This wallet exceeds the supported manual quantity limit."
    }
}

data class ManualCryptoTrade(
    val operationId: String,
    val kind: TransactionKind,
    val walletId: String,
    val quoteAccountId: String,
    val quantity: CryptoQuantity,
    val gross: EntryAmount,
    val fee: Money,
    val time: FinancialTime,
    val clientCreatedAt: Instant,
    val expectedVersion: Long,
) {
    init {
        require(kind == TransactionKind.CRYPTO_BUY || kind == TransactionKind.CRYPTO_SELL)
        require(walletId.isNotBlank() && quoteAccountId.isNotBlank() && walletId != quoteAccountId)
        requireManualCryptoQuantity(quantity)
        require(quantity.atomic.signum() > 0) { "Enter a positive crypto quantity." }
        require(gross.money.currency == FiatCurrency.LKR && fee.currency == FiatCurrency.LKR)
        require(fee.minor >= 0L)
        require(kind != TransactionKind.CRYPTO_SELL || fee.minor < gross.money.minor) {
            "A sell fee must be less than the gross proceeds."
        }
        require(expectedVersion >= 0)
    }

    fun matches(event: FinancialTransaction): Boolean {
        val trade = event.cryptoTrade ?: return false
        return event.id == operationId && event.kind == kind &&
            event.financialTime == time && event.clientCreatedAt == clientCreatedAt &&
            trade.assetAccountId == walletId && trade.quoteAccountId == quoteAccountId &&
            trade.quantity == quantity && trade.grossQuoteAmount == NativeAmount.Fiat(gross.money) &&
            (trade.fees.singleOrNull()?.lkrAmount ?: Money.lkr(0L)) == fee &&
            event.components.firstOrNull { it.nature == ComponentNature.SPENDING }?.categoryId ==
                CRYPTO_FEE_CATEGORY_ID.takeIf { fee.minor > 0L }
    }
}

data class ManualCryptoTradeResult(val event: FinancialTransaction, val position: CryptoPosition)

fun planManualCryptoTrade(command: ManualCryptoTrade, current: CryptoPosition): ManualCryptoTradeResult {
    require(command.walletId == current.accountId && current.version == command.expectedVersion)
    val buy = command.kind == TransactionKind.CRYPTO_BUY
    val gross = command.gross.money
    val fee = command.fee
    val cashAmount = if (buy) -(gross + fee) else gross + -fee
    val disposal = if (buy) null else CryptoCostBasisCalculations.dispose(
        current, command.operationId, command.quantity, gross, listOf(fee),
    )
    val next = disposal?.nextPosition ?: CryptoCostBasisCalculations.acquire(
        current, command.operationId, command.quantity, -cashAmount,
    )
    requireManualCryptoQuantity(next.quantity)
    val cryptoMovement = if (buy) command.quantity else command.quantity.copy(atomic = -command.quantity.atomic)
    val fees = if (fee.minor == 0L) emptyList() else listOf(
        TradeFee(command.quoteAccountId, NativeAmount.Fiat(fee), fee),
    )
    val reports = buildList {
        if (fee.minor > 0L) {
            add(ReportComponent(
                ComponentNature.SPENDING, KnownLkrAmount.Known(fee),
                categoryId = CRYPTO_FEE_CATEGORY_ID,
                commitmentClass = CommitmentClass.DISCRETIONARY,
            ))
        }
        disposal?.let { add(ReportComponent(ComponentNature.REALIZED_CRYPTO, it.realizedPnl)) }
    }
    val event = FinancialTransaction(
        operationId = command.operationId,
        kind = command.kind,
        status = TransactionStatus.POSTED,
        financialTime = command.time,
        clientCreatedAt = command.clientCreatedAt,
        movements = listOf(
            AccountMovement(command.walletId, NativeAmount.Crypto(cryptoMovement)),
            AccountMovement(command.quoteAccountId, NativeAmount.Fiat(cashAmount)),
        ),
        components = reports,
        cryptoTrade = CryptoTrade(
            command.walletId, command.quoteAccountId, command.quantity, NativeAmount.Fiat(gross), fees,
            gross, allocatedCost = disposal?.allocatedCost, realizedPnl = disposal?.realizedPnl,
        ),
    )
    return ManualCryptoTradeResult(event, next)
}
