package com.pocketpath.domain.validation

import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import java.math.BigInteger

/** Local structure and arithmetic only; not a replacement for atomic repository/rules validation. */
object TransactionValidator {
    fun errors(event: FinancialTransaction): List<String> = buildList {
        fun check(valid: Boolean, message: String) { if (!valid) add(message) }
        val m = event.movements
        val c = event.components
        val r = event.reservationEffects
        check(event.id.isNotBlank() && '/' !in event.id, "Invalid operation/document ID")
        check(event.schemaVersion == 1 && event.revision >= 0, "Invalid version")
        check(m.size <= 4 && c.size <= 4 && r.size <= 4 && event.valuations.size <= 4, "Envelope collection exceeds four items")
        check(m.isNotEmpty() || r.isNotEmpty(), "Event needs movements or reservation effects")
        check(event.note == null || event.note.length <= 1000, "Note exceeds 1000 characters")
        check((event.status == TransactionStatus.VOIDED) == (event.voidMetadata != null), "Void status/metadata mismatch")
        check(event.replacesTransactionId != event.id, "Replacement cannot reference itself")
        listOf(event.occurrenceId, event.expectedPaymentId, event.originalTransactionId, event.goalPlanId,
            event.replacesTransactionId).filterNotNull().plus(event.accountIds).plus(event.categoryIds)
            .plus(event.goalIds).plus(event.incomeSourceIds)
            .plus(event.valuations.mapNotNull { it.rateSnapshotId })
            .plus(c.mapNotNull { it.rateSnapshotId }).forEach {
            check(it.isNotBlank() && '/' !in it, "Invalid same-user reference ID")
        }
        check(event.expectedPaymentId == null || event.kind == TransactionKind.INCOME, "Expected-payment link requires income")
        check(event.occurrenceId == null || event.kind in setOf(TransactionKind.INCOME, TransactionKind.EXPENSE), "Occurrence link requires receipt or bill payment")
        check(event.originalTransactionId == null || event.kind in setOf(TransactionKind.REFUND, TransactionKind.REIMBURSEMENT), "Original link requires refund/reimbursement")
        check(event.goalPlanId == null || r.isNotEmpty(), "Goal plan requires reservation effects")
        check(event.valuations.all { v -> m.any { it.accountId == v.accountId && it.amount == v.nativeAmount } }, "Valuation must match a movement")
        check(event.valuations.map { it.accountId to it.nativeAmount }.distinct().size == event.valuations.size, "Duplicate valuation")
        check((event.cryptoTrade != null) == (event.kind in setOf(TransactionKind.CRYPTO_BUY, TransactionKind.CRYPTO_SELL)), "Trade details incompatible with kind")
        fun only(vararg natures: ComponentNature) = c.all { it.nature in natures }
        val positive = m.any { it.amount.atomicUnits().signum() > 0 }
        val negative = m.any { it.amount.atomicUnits().signum() < 0 }
        when (event.kind) {
            TransactionKind.OPENING -> check(m.size == 1 && c.isEmpty() && r.isEmpty(), "Opening is one movement, not earnings")
            TransactionKind.ADJUSTMENT -> check(m.isNotEmpty() && c.isEmpty() && r.isEmpty(), "Adjustment is not earnings/consumption")
            TransactionKind.INCOME -> {
                check(positive && c.any { it.nature == ComponentNature.EARNING } && r.isEmpty(), "Income needs receipt and earnings")
                check(only(ComponentNature.EARNING, ComponentNature.SPENDING), "Invalid income report nature")
            }
            TransactionKind.EXPENSE, TransactionKind.GOAL_PURCHASE -> {
                check(negative && m.all { it.amount.atomicUnits().signum() < 0 }, "Expense needs paying movements")
                check(c.isNotEmpty() && only(ComponentNature.SPENDING), "Expense needs spending components")
                check(if (event.kind == TransactionKind.EXPENSE) r.isEmpty() else r.isNotEmpty() && r.all { it.amountLkr.minor < 0 }, "Invalid purchase reservation effects")
            }
            TransactionKind.TRANSFER -> {
                check(r.all { it.amountLkr.minor > 0 }, "Transfer-linked contributions reserve existing backing")
                check(positive && negative && m.map { it.accountId }.distinct().size >= 2, "Transfer needs source and destination")
                check(m.map { it.amount.asset }.distinct().size == 1, "Use FX_CONVERSION for unlike assets")
                check(only(ComponentNature.SPENDING), "Transfer principal is not earnings/spending")
                if (m.isNotEmpty() && m.map { it.amount.asset }.distinct().size == 1) {
                    val asset = m.first().amount.asset
                    val net = m.fold(BigInteger.ZERO) { total, movement -> total + movement.amount.atomicUnits() }
                    val fees = c.fold(BigInteger.ZERO) { total, component ->
                        val units = if (asset == FiatCurrency.LKR) {
                            (component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor?.let(BigInteger::valueOf)
                        } else component.originalAmount?.takeIf { it.asset == asset }?.atomicUnits()
                        check(units != null && units.signum() >= 0, "Fee needs native quantity for this asset")
                        total + (units ?: BigInteger.ZERO)
                    }
                    check(net == -fees, "Transfer principal must cancel; fee movement/component must occur once")
                }
            }
            TransactionKind.FX_CONVERSION -> {
                check(positive && negative && m.all { it.amount.asset is FiatCurrency } && m.map { it.amount.asset }.distinct().size >= 2 && r.isEmpty(), "FX requires unlike actual fiat amounts")
                check(only(ComponentNature.SPENDING, ComponentNature.REALIZED_FX), "FX principal is not income")
            }
            TransactionKind.CRYPTO_BUY, TransactionKind.CRYPTO_SELL -> {
                check(positive && negative && r.isEmpty(), "Trade needs asset and quote movements")
                check(only(ComponentNature.SPENDING, ComponentNature.REALIZED_CRYPTO), "Trade proceeds are not earnings")
                event.cryptoTrade?.let { trade ->
                    val expectedSign = if (event.kind == TransactionKind.CRYPTO_BUY) 1 else -1
                    val expected = mutableMapOf<Pair<String, Asset>, BigInteger>()
                    fun addExpected(accountId: String, asset: Asset, units: BigInteger) {
                        val key = accountId to asset
                        expected[key] = (expected[key] ?: BigInteger.ZERO) + units
                    }
                    val sign = BigInteger.valueOf(expectedSign.toLong())
                    addExpected(trade.assetAccountId, trade.quantity.asset, trade.quantity.atomic * sign)
                    addExpected(trade.quoteAccountId, trade.grossQuoteAmount.asset, -trade.grossQuoteAmount.atomicUnits() * sign)
                    trade.fees.forEach { fee -> addExpected(fee.accountId, fee.amount.asset, -fee.amount.atomicUnits()) }
                    val actual = m.groupBy { it.accountId to it.amount.asset }.mapValues { (_, movements) ->
                        movements.fold(BigInteger.ZERO) { sum, movement -> sum + movement.amount.atomicUnits() }
                    }
                    check(actual == expected, "Trade principal and fees must match actual native movements once")
                    val reportFees = c.filter { it.nature == ComponentNature.SPENDING }.fold(BigInteger.ZERO) { sum, component ->
                        sum + BigInteger.valueOf((component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor ?: 0)
                    }
                    check(reportFees == trade.fees.fold(BigInteger.ZERO) { sum, fee -> sum + BigInteger.valueOf(fee.lkrAmount.minor) }, "Trade fee report mismatch")
                    if (event.kind == TransactionKind.CRYPTO_SELL) {
                        check(trade.allocatedCost != null && trade.realizedPnl != null, "Sale must retain known/unknown cost and profit")
                        check(c.filter { it.nature == ComponentNature.REALIZED_CRYPTO }.singleOrNull()?.lkrAmount == trade.realizedPnl, "Sale report must preserve realized profit uncertainty")
                    } else check(trade.allocatedCost == null && trade.realizedPnl == null && only(ComponentNature.SPENDING), "Buy has no realized sale result")
                }
            }
            TransactionKind.REFUND, TransactionKind.REIMBURSEMENT -> {
                val nature = if (event.kind == TransactionKind.REFUND) ComponentNature.REFUND else ComponentNature.REIMBURSEMENT
                check(positive && m.all { it.amount.atomicUnits().signum() > 0 } && c.isNotEmpty() && only(nature) && r.isEmpty(), "Invalid refund/reimbursement")
                check(event.kind != TransactionKind.REFUND || event.originalTransactionId != null, "Refund must link original expense")
            }
            TransactionKind.GOAL_RESERVE -> check(r.isNotEmpty() && r.all { it.amountLkr.minor > 0 } && m.isEmpty() && c.isEmpty(), "Pure reservation has no account/report credit")
            TransactionKind.GOAL_RELEASE -> check(r.isNotEmpty() && r.all { it.amountLkr.minor < 0 } && m.isEmpty() && c.isEmpty(), "Release only changes earmarks")
        }
        if (m.isNotEmpty() && m.all { it.amount.asset == FiatCurrency.LKR } && event.kind in setOf(
                TransactionKind.INCOME, TransactionKind.EXPENSE, TransactionKind.GOAL_PURCHASE,
                TransactionKind.REFUND, TransactionKind.REIMBURSEMENT)) {
            val netMovement = m.fold(BigInteger.ZERO) { total, movement -> total + movement.amount.atomicUnits() }
            val netReport = c.fold(BigInteger.ZERO) { total, component ->
                val amount = (component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor ?: 0L
                total + BigInteger.valueOf(if (component.nature == ComponentNature.SPENDING) -amount else amount)
            }
            check(netMovement == netReport, "LKR movements and report magnitudes disagree")
        }
        if (m.any { it.amount.asset != FiatCurrency.LKR } && event.kind in setOf(
                TransactionKind.INCOME, TransactionKind.EXPENSE, TransactionKind.GOAL_PURCHASE,
                TransactionKind.REFUND, TransactionKind.REIMBURSEMENT)) {
            val foreign = m.filter { it.amount.asset != FiatCurrency.LKR }
            check(foreign.all { movement -> event.valuations.any { it.accountId == movement.accountId && it.nativeAmount == movement.amount } },
                "Foreign receipt/payment needs retained historical movement valuation")
            val historicalNet = m.fold(BigInteger.ZERO) { total, movement ->
                total + if (movement.amount.asset == FiatCurrency.LKR) movement.amount.atomicUnits()
                else BigInteger.valueOf(event.valuations.firstOrNull { it.accountId == movement.accountId && it.nativeAmount == movement.amount }?.lkrAmount?.minor ?: 0)
            }
            val reportNet = c.fold(BigInteger.ZERO) { total, component ->
                val amount = (component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor ?: 0L
                total + BigInteger.valueOf(if (component.nature == ComponentNature.SPENDING) -amount else amount)
            }
            check(historicalNet == reportNet, "Historical movement valuations and report amounts disagree")
        }
    }
}
