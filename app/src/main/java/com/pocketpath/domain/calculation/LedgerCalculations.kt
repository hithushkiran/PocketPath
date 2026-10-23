package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.LedgerScope
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface BalanceUnits {
    val asset: Asset
    data class Fiat(override val asset: FiatCurrency, val minor: Long) : BalanceUnits
    data class Crypto(override val asset: CryptoAsset, val atomic: BigInteger) : BalanceUnits
}
enum class BalanceWarning { NEGATIVE_RECORDED_BALANCE }
data class AccountBalance(val accountId: String, val units: BalanceUnits, val warnings: Set<BalanceWarning>)

/** Complete native movement history only. Never add report amounts or reservation effects here. */
fun accountBalance(account: Account, ledger: CompleteLedger, asOf: Instant = Instant.MAX): AccountBalance {
    require(ledger.scope == LedgerScope.AllEvents || ledger.scope == LedgerScope.AccountEvents(account.id))
    val sum = ledger.events.asSequence()
        .filter { it.status == TransactionStatus.POSTED && it.financialTime.occurredAt <= asOf }
        .flatMap { it.movements.asSequence() }.filter { it.accountId == account.id }
        .fold(BigInteger.ZERO) { total, movement ->
            require(movement.amount.asset == account.asset) { "Movement asset/scale differs from account" }
            total + movement.amount.atomicUnits()
        }
    val units = when (val asset = account.asset) {
        is FiatCurrency -> BalanceUnits.Fiat(asset, sum.toLongChecked())
        is CryptoAsset -> BalanceUnits.Crypto(asset, sum)
    }
    return AccountBalance(account.id, units, if (sum.signum() < 0) setOf(BalanceWarning.NEGATIVE_RECORDED_BALANCE) else emptySet())
}

/** Period boundaries use financial occurrence instants in an explicit reporting timezone, never server audit time. */
data class ReportPeriod(val start: LocalDate, val endInclusive: LocalDate, val timeZone: ZoneId) {
    init { require(endInclusive >= start) }
    fun contains(event: FinancialTransaction): Boolean =
        event.financialTime.occurredAt.atZone(timeZone).toLocalDate() in start..endInclusive
}

data class AnalyticalTotal(val knownLkrMinor: Long, val unknownReasons: List<String>) {
    val isComplete: Boolean get() = unknownReasons.isEmpty()
}
data class PeriodReportTotals(
    val earningsLkrMinor: Long, val grossSpendingLkrMinor: Long, val refundsLkrMinor: Long,
    val reimbursementsLkrMinor: Long, val netSpendingLkrMinor: Long,
    val realizedCrypto: AnalyticalTotal, val realizedFx: AnalyticalTotal,
)

fun periodReportTotals(ledger: CompleteLedger, period: ReportPeriod): PeriodReportTotals {
    require(ledger.scope == LedgerScope.AllEvents) { "Period report requires complete user event scope" }
    val amounts = mutableMapOf<ComponentNature, Long>()
    val unknowns = mutableMapOf<ComponentNature, MutableList<String>>()
    ledger.events.filter { it.status == TransactionStatus.POSTED && period.contains(it) }.forEach { event ->
        event.components.forEach { component ->
            when (val value = component.lkrAmount) {
                is KnownLkrAmount.Known -> amounts[component.nature] = Math.addExact(amounts[component.nature] ?: 0, value.amount.minor)
                is KnownLkrAmount.Unknown -> unknowns.getOrPut(component.nature) { mutableListOf() }.add(value.reason)
            }
        }
    }
    fun amount(nature: ComponentNature) = amounts[nature] ?: 0L
    fun analytical(nature: ComponentNature) = AnalyticalTotal(amount(nature), unknowns[nature]?.toList() ?: emptyList())
    return PeriodReportTotals(
        amount(ComponentNature.EARNING), amount(ComponentNature.SPENDING), amount(ComponentNature.REFUND),
        amount(ComponentNature.REIMBURSEMENT), Math.subtractExact(amount(ComponentNature.SPENDING), amount(ComponentNature.REFUND)),
        analytical(ComponentNature.REALIZED_CRYPTO), analytical(ComponentNature.REALIZED_FX),
    )
}

/** Basic earmark sum only; available backing, monthly plans and goal-impact engines are deferred. */
fun goalEarmarkLkrMinor(ledger: CompleteLedger, goalId: String): Long {
    require(ledger.scope == LedgerScope.AllEvents)
    return ledger.events
    .filter { it.status == TransactionStatus.POSTED }.flatMap { it.reservationEffects }
    .filter { it.goalId == goalId }.fold(0L) { total, effect -> Math.addExact(total, effect.amountLkr.minor) }
}
