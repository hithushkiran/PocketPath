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

data class BudgetProgress(
    val budget: Budget,
    val period: ReportPeriod,
    val spentLkrMinor: Long,
    val limitLkrMinor: Long,
    val remainingLkrMinor: Long,
    val percentageUsed: Double,
    val isOverBudget: Boolean,
)

data class CommitmentClassTotal(
    val commitmentClass: CommitmentClass,
    val spentLkrMinor: Long,
    val percentageOfTotal: Double,
)

data class CommitmentBreakdown(
    val period: ReportPeriod,
    val totalSpentLkrMinor: Long,
    val totals: Map<CommitmentClass, CommitmentClassTotal>,
)

fun budgetPeriodForDate(
    periodType: BudgetPeriod,
    asOfDate: LocalDate,
    timeZone: ZoneId,
    weekStartsOn: Int = 1,
): ReportPeriod {
    val (start, end) = when (periodType) {
        BudgetPeriod.MONTHLY -> {
            val startOfMonth = asOfDate.withDayOfMonth(1)
            val endOfMonth = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth())
            startOfMonth to endOfMonth
        }
        BudgetPeriod.WEEKLY -> {
            val dayOfWeekVal = asOfDate.dayOfWeek.value
            val offset = (dayOfWeekVal - weekStartsOn + 7) % 7
            val startOfWeek = asOfDate.minusDays(offset.toLong())
            val endOfWeek = startOfWeek.plusDays(6)
            startOfWeek to endOfWeek
        }
    }
    return ReportPeriod(start, end, timeZone)
}

fun calculateBudgetProgress(
    ledger: CompleteLedger,
    budget: Budget,
    asOfDate: LocalDate,
    timeZone: ZoneId,
    weekStartsOn: Int = 1,
): BudgetProgress {
    require(ledger.scope == LedgerScope.AllEvents) { "Budget progress requires complete ledger scope" }
    val period = budgetPeriodForDate(budget.periodType, asOfDate, timeZone, weekStartsOn)

    var spentMinor = 0L
    ledger.events
        .filter { it.status == TransactionStatus.POSTED && period.contains(it) }
        .forEach { event ->
            event.components.forEach { component ->
                if (component.nature == ComponentNature.SPENDING) {
                    val matchesScope = when (budget.scope) {
                        BudgetScope.OVERALL_DISCRETIONARY ->
                            component.commitmentClass == CommitmentClass.DISCRETIONARY
                        BudgetScope.CATEGORY_DISCRETIONARY ->
                            component.categoryId == budget.categoryId &&
                                component.commitmentClass == CommitmentClass.DISCRETIONARY
                    }
                    if (matchesScope) {
                        val amount = (component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor ?: 0L
                        spentMinor = Math.addExact(spentMinor, amount)
                    }
                }
            }
        }

    val limitMinor = budget.limit.money.minor
    val remainingMinor = Math.subtractExact(limitMinor, spentMinor)
    val percentage = if (limitMinor <= 0L) {
        if (spentMinor > 0L) 100.0 else 0.0
    } else {
        (spentMinor.toDouble() / limitMinor.toDouble()) * 100.0
    }
    val isOver = spentMinor > limitMinor

    return BudgetProgress(
        budget = budget,
        period = period,
        spentLkrMinor = spentMinor,
        limitLkrMinor = limitMinor,
        remainingLkrMinor = remainingMinor,
        percentageUsed = percentage,
        isOverBudget = isOver,
    )
}

fun calculateCommitmentBreakdown(
    ledger: CompleteLedger,
    period: ReportPeriod,
): CommitmentBreakdown {
    require(ledger.scope == LedgerScope.AllEvents) { "Commitment breakdown requires complete ledger scope" }
    val totalsMap = mutableMapOf<CommitmentClass, Long>()
    CommitmentClass.entries.forEach { totalsMap[it] = 0L }

    ledger.events
        .filter { it.status == TransactionStatus.POSTED && period.contains(it) }
        .forEach { event ->
            event.components.forEach { component ->
                val cls = component.commitmentClass
                if (component.nature == ComponentNature.SPENDING && cls != null) {
                    val current = totalsMap[cls] ?: 0L
                    val amount = (component.lkrAmount as? KnownLkrAmount.Known)?.amount?.minor ?: 0L
                    totalsMap[cls] = Math.addExact(current, amount)
                }
            }
        }

    val totalSpent = totalsMap.values.fold(0L) { acc, v -> Math.addExact(acc, v) }
    val classTotals = CommitmentClass.entries.associateWith { cls ->
        val spent = totalsMap[cls] ?: 0L
        val pct = if (totalSpent > 0L) (spent.toDouble() / totalSpent.toDouble()) * 100.0 else 0.0
        CommitmentClassTotal(cls, spent, pct)
    }

    return CommitmentBreakdown(
        period = period,
        totalSpentLkrMinor = totalSpent,
        totals = classTotals,
    )
}
