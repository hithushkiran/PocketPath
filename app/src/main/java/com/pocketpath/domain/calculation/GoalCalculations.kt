package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.Goal
import com.pocketpath.domain.model.GoalPlan
import com.pocketpath.domain.model.GoalPlanState
import com.pocketpath.domain.model.GoalState
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.LedgerScope
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class GoalProgress(
    val goalId: String,
    val targetLkrMinor: Long,
    val reservedLkrMinor: Long,
    val remainingLkrMinor: Long,
    val reservationsByAccountLkrMinor: Map<String, Long>,
    val planId: String?,
    val netPlanContributionLkrMinor: Long?,
    val remainingPlannedLkrMinor: Long?,
)

/** Read-only projection. The caller must supply complete, user-scoped history. */
fun calculateGoalProgress(
    goal: Goal,
    ledger: CompleteLedger,
    asOf: Instant,
    plan: GoalPlan? = null,
): GoalProgress {
    require(ledger.scope == LedgerScope.AllEvents) {
        "Goal progress requires complete user history."
    }
    require(plan == null || plan.goalId == goal.id) {
        "The contribution plan belongs to another goal."
    }

    val backing = mutableMapOf<String, Long>()
    var planNet = 0L

    ledger.events.asSequence()
        .filter { it.status == TransactionStatus.POSTED && it.financialTime.occurredAt <= asOf }
        .forEach { event ->
            event.reservationEffects.filter { it.goalId == goal.id }.forEach { effect ->
                val accountId = effect.backingAccountId
                backing[accountId] = Math.addExact(
                    backing[accountId] ?: 0L,
                    effect.amountLkr.minor,
                )
                if (plan != null && event.goalPlanId == plan.id) {
                    planNet = Math.addExact(planNet, effect.amountLkr.minor)
                }
            }
        }

    require(backing.values.all { it >= 0L }) {
        "Goal releases exceed recorded reservations for a backing account."
    }
    val reserved = backing.values.fold(0L) { total, amount -> Math.addExact(total, amount) }
    val remaining = maxOf(0L, Math.subtractExact(goal.target.money.minor, reserved))
    val remainingPlan = plan?.let {
        if (it.state == GoalPlanState.ACTIVE && goal.state == GoalState.ACTIVE) {
            maxOf(0L, Math.subtractExact(it.plannedContribution.minor, planNet))
        } else {
            0L
        }
    }

    return GoalProgress(
        goalId = goal.id,
        targetLkrMinor = goal.target.money.minor,
        reservedLkrMinor = reserved,
        remainingLkrMinor = remaining,
        reservationsByAccountLkrMinor = backing.toMap(),
        planId = plan?.id,
        netPlanContributionLkrMinor = if (plan == null) null else planNet,
        remainingPlannedLkrMinor = remainingPlan,
    )
}

enum class SavingsPaceStatus { SCHEDULED, TARGET_REACHED, REPLAN_REQUIRED, INACTIVE }

data class GoalInstallment(val date: LocalDate, val amountLkrMinor: Long)

data class SavingsPace(
    val status: SavingsPaceStatus,
    val remainingLkrMinor: Long,
    val installments: List<GoalInstallment>,
)

data class SavedGoalProgress(
    val goal: Goal,
    val confirmedReservedMinor: Long,
    val pace: SavingsPace,
)

/** Preserve the anchor day after a short month and exclude elapsed dates. */
fun monthlyGoalDates(first: LocalDate, deadline: LocalDate, asOfDate: LocalDate = first): List<LocalDate> {
    require(first <= deadline)
    val start = maxOf(first, asOfDate)
    if (start > deadline) return emptyList()
    val dates = mutableListOf<LocalDate>()
    var month = YearMonth.from(start)
    val lastMonth = YearMonth.from(deadline)
    while (month <= lastMonth) {
        val date = OccurrenceCalculator.monthlyDate(first, month)
        if (date in start..deadline) dates += date
        if (month == lastMonth) break
        month = month.plusMonths(1)
    }
    return dates
}

fun calculateSavedGoalProgress(goal: Goal, reservedLkrMinor: Long, asOfDate: LocalDate): SavedGoalProgress {
    val dates = goal.firstContributionDate?.let { monthlyGoalDates(it, goal.deadline, asOfDate) }.orEmpty()
    return SavedGoalProgress(
        goal, reservedLkrMinor, calculateSavingsPace(goal, reservedLkrMinor, asOfDate, dates),
    )
}

/** Dates are supplied explicitly: no guessed month count or device-clock dependency. */
fun calculateSavingsPace(
    goal: Goal,
    reservedLkrMinor: Long,
    asOfDate: LocalDate,
    remainingContributionDates: List<LocalDate>,
): SavingsPace {
    require(reservedLkrMinor >= 0L)
    val remaining = maxOf(0L, Math.subtractExact(goal.target.money.minor, reservedLkrMinor))

    if (goal.state != GoalState.ACTIVE) {
        return SavingsPace(SavingsPaceStatus.INACTIVE, remaining, emptyList())
    }
    if (remaining == 0L) {
        return SavingsPace(SavingsPaceStatus.TARGET_REACHED, 0L, emptyList())
    }
    if (goal.deadline < asOfDate) {
        return SavingsPace(SavingsPaceStatus.REPLAN_REQUIRED, remaining, emptyList())
    }

    val dates = remainingContributionDates.sorted()
    require(dates.distinct().size == dates.size) { "Duplicate contribution dates." }
    require(dates.all { it >= asOfDate && it >= goal.startDate && it <= goal.deadline }) {
        "Contribution dates must be remaining dates inside the goal period."
    }
    if (dates.isEmpty()) {
        return SavingsPace(SavingsPaceStatus.REPLAN_REQUIRED, remaining, emptyList())
    }

    val count = dates.size.toLong()
    val each = remaining / count
    val extraCents = remaining % count
    val installments = dates.mapIndexed { index, date ->
        GoalInstallment(date, Math.addExact(each, if (index.toLong() < extraCents) 1L else 0L))
    }
    return SavingsPace(SavingsPaceStatus.SCHEDULED, remaining, installments)
}
