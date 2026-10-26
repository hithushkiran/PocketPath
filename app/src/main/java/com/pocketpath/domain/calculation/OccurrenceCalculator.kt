package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import java.time.LocalDate
import java.time.YearMonth

/**
 * Deterministically expands an income-reminder schedule into candidate occurrences.
 *
 * This has no clock, device timezone, persistence, notification, or state-transition behaviour.
 * Callers provide a local-date window and the resulting occurrences retain the schedule timezone.
 */
object OccurrenceCalculator {
    fun incomeReminderOccurrences(
        schedule: Schedule,
        windowStart: LocalDate,
        windowEndInclusive: LocalDate,
    ): List<Occurrence> {
        require(schedule.kind == ScheduleKind.INCOME_REMINDER) {
            "This calculator only expands income reminder schedules."
        }
        require(windowEndInclusive >= windowStart) { "Occurrence window ends before it starts." }

        val lowerBound = maxOf(windowStart, schedule.activeFrom, schedule.recurrence.anchorDate)
        val upperBound = minOf(windowEndInclusive, schedule.endDate ?: windowEndInclusive)
        if (upperBound < lowerBound) return emptyList()

        return nominalDates(schedule, upperBound)
            .filter { it >= lowerBound }
            .map { nominalDate ->
                Occurrence(
                    scheduleId = schedule.id,
                    nominalDate = nominalDate,
                    effectiveDueDate = nominalDate,
                    kind = ScheduleKind.INCOME_REMINDER,
                    titleSnapshot = schedule.title,
                    expectedAmount = schedule.expectedAmount,
                    timeZone = schedule.timeZone,
                    sourceId = schedule.incomeSourceId,
                )
            }
            .toList()
    }

    private fun nominalDates(schedule: Schedule, upperBound: LocalDate): Sequence<LocalDate> = sequence {
        val recurrence = schedule.recurrence
        when (recurrence.frequency) {
            Frequency.WEEKLY -> {
                var date = recurrence.anchorDate
                while (date <= upperBound) {
                    yield(date)
                    date = date.plusWeeks(1)
                }
            }

            Frequency.MONTHLY -> {
                require(recurrence.anchorDate.dayOfMonth <= 28) {
                    "Monthly anchors on days 29-31 require an approved short-month policy."
                }
                var month = YearMonth.from(recurrence.anchorDate)
                while (!month.atDay(1).isAfter(upperBound)) {
                    yield(month.atDay(recurrence.anchorDate.dayOfMonth))
                    month = month.plusMonths(1)
                }
            }

            Frequency.YEARLY -> {
                require(!(recurrence.anchorDate.monthValue == 2 && recurrence.anchorDate.dayOfMonth == 29)) {
                    "A yearly 29 February anchor requires an approved leap-year policy."
                }
                var year = recurrence.anchorDate.year
                while (year <= upperBound.year) {
                    yield(LocalDate.of(year, recurrence.anchorDate.monthValue, recurrence.anchorDate.dayOfMonth))
                    year++
                }
            }
        }
    }
}
