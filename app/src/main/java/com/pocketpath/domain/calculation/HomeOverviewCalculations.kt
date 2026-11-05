package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.MonthlyBill
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.LedgerScope
import java.time.LocalDate
import java.time.ZoneId

data class HomeLedgerSummary(
    val date: LocalDate,
    val receivedMinor: Long,
    val netSpendingMinor: Long,
    val reservedByAccount: Map<String, Long>,
    val reservedByGoal: Map<String, Long>,
    val billPaymentsByOccurrence: Map<String, Long>,
    val receivedBySourceMinor: Map<String, Long>,
    val realizedCrypto: AnalyticalTotal? = null,
)

fun summarizeHomeLedger(ledger: CompleteLedger, date: LocalDate, zone: ZoneId): HomeLedgerSummary {
    require(ledger.scope == LedgerScope.AllEvents)
    val reservedByAccount = mutableMapOf<String, Long>()
    val reservedByGoal = mutableMapOf<String, Long>()
    val billPayments = mutableMapOf<String, Long>()
    val receivedBySource = mutableMapOf<String, Long>()
    val period = ReportPeriod(date.withDayOfMonth(1), date, zone)
    for (event in ledger.events) {
        if (event.status != TransactionStatus.POSTED) continue
        for (effect in event.reservationEffects) {
            reservedByAccount[effect.backingAccountId] = Math.addExact(
                reservedByAccount[effect.backingAccountId] ?: 0L, effect.amountLkr.minor,
            )
            reservedByGoal[effect.goalId] = Math.addExact(
                reservedByGoal[effect.goalId] ?: 0L, effect.amountLkr.minor,
            )
        }
        if (event.kind == TransactionKind.INCOME && period.contains(event)) {
            for (component in event.components) {
                if (component.nature != ComponentNature.EARNING) continue
                val sourceId = requireNotNull(component.incomeSourceId)
                val amount = (component.lkrAmount as KnownLkrAmount.Known).amount.minor
                receivedBySource[sourceId] = Math.addExact(receivedBySource[sourceId] ?: 0L, amount)
            }
        }
        val occurrenceId = event.occurrenceId ?: continue
        if (event.kind != TransactionKind.EXPENSE) continue
        val amount = (event.components.single().lkrAmount as KnownLkrAmount.Known).amount.minor
        billPayments[occurrenceId] = Math.addExact(billPayments[occurrenceId] ?: 0L, amount)
    }
    val reports = periodReportTotals(ledger, period)
    val hasCryptoSale = ledger.events.any {
        it.kind == TransactionKind.CRYPTO_SELL && it.status == TransactionStatus.POSTED && period.contains(it)
    }
    return HomeLedgerSummary(
        date, reports.earningsLkrMinor, reports.netSpendingLkrMinor, reservedByAccount.toMap(),
        reservedByGoal.toMap(), billPayments.toMap(), receivedBySource.toMap(),
        reports.realizedCrypto.takeIf { hasCryptoSale },
    )
}

/** Excludes hypothetical goal schedules. Two independent listeners must agree on bill payments. */
fun recordedHomeFunds(
    ledger: HomeLedgerSummary,
    everydayBalanceMinor: Long,
    everydayAccountIds: Set<String>,
    bills: List<MonthlyBill>,
): FundsPlanningResult.Ready? {
    val observedPayments = bills.filter { it.paidMinor != 0L }
        .associate { it.occurrence.id to it.paidMinor }
    if (observedPayments != ledger.billPaymentsByOccurrence) return null

    val horizon = ledger.date.withDayOfMonth(ledger.date.lengthOfMonth())
    val reserved = ledger.reservedByAccount.entries
        .filter { it.key in everydayAccountIds }
        .fold(0L) { sum, (_, amount) -> Math.addExact(sum, amount) }
    val unpaid = bills.filter { it.occurrence.effectiveDueDate <= horizon }
        .fold(0L) { sum, bill -> Math.addExact(sum, bill.remainingMinor) }
    return calculateFundsPlan(FundsPlanningInput(
        asOfDate = ledger.date,
        eligibleBalanceMinor = everydayBalanceMinor,
        reservedInEligibleAccountsMinor = reserved,
        outstandingCommitmentsMinor = unpaid,
        remainingPlannedContributionsMinor = 0L,
    )) as FundsPlanningResult.Ready
}
