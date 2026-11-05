package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.NativeAmount
import java.time.Instant
import java.time.LocalDate

sealed interface IncomeReminderAction {
    data class Adjust(val amount: EntryAmount, val dueOn: LocalDate) : IncomeReminderAction
    data class Snooze(val until: Instant) : IncomeReminderAction
    data object Skip : IncomeReminderAction
    data object Reopen : IncomeReminderAction
    data class Confirm(val amount: EntryAmount, val receivedOn: LocalDate, val enteredAt: Instant) : IncomeReminderAction
}

fun scheduledIncomeReceipt(
    schedule: Schedule,
    occurrence: Occurrence,
    action: IncomeReminderAction.Confirm,
): FinancialTransaction {
    require(occurrence.scheduleId == schedule.id && occurrence.sourceId == schedule.incomeSourceId)
    require(action.amount.money.currency == FiatCurrency.LKR)
    val sourceId = requireNotNull(schedule.incomeSourceId)
    return FinancialTransaction(
        operationId = StableIds.scheduledReceipt(occurrence.id),
        kind = TransactionKind.INCOME,
        status = TransactionStatus.POSTED,
        financialTime = FinancialTime(
            action.receivedOn.atStartOfDay(schedule.timeZone).toInstant(),
            action.receivedOn,
            schedule.timeZone,
        ),
        clientCreatedAt = action.enteredAt,
        movements = listOf(AccountMovement(requireNotNull(schedule.preferredAccountId), NativeAmount.Fiat(action.amount.money))),
        components = listOf(ReportComponent(
            ComponentNature.EARNING, KnownLkrAmount.Known(action.amount.money), incomeSourceId = sourceId,
        )),
        occurrenceId = occurrence.id,
    )
}
