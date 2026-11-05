package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

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

        return nominalDates(schedule, lowerBound, upperBound)
            .filter { it in lowerBound..upperBound }
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

    fun monthlyDate(anchor: LocalDate, month: YearMonth): LocalDate =
        month.atDay(minOf(anchor.dayOfMonth, month.lengthOfMonth()))

    private fun nominalDates(
        schedule: Schedule, lowerBound: LocalDate, upperBound: LocalDate,
    ): Sequence<LocalDate> = sequence {
        val recurrence = schedule.recurrence
        when (recurrence.frequency) {
            Frequency.WEEKLY -> {
                val weeks = ChronoUnit.WEEKS.between(recurrence.anchorDate, lowerBound)
                var date = recurrence.anchorDate.plusWeeks(weeks)
                while (date <= upperBound) {
                    yield(date)
                    date = date.plusWeeks(1)
                }
            }

            Frequency.MONTHLY -> {
                var month = YearMonth.from(lowerBound)
                while (!month.atDay(1).isAfter(upperBound)) {
                    // Keep the original anchor on the schedule; only this occurrence's date clamps.
                    yield(monthlyDate(recurrence.anchorDate, month))
                    month = month.plusMonths(1)
                }
            }

            Frequency.YEARLY -> {
                var year = lowerBound.year
                while (year <= upperBound.year) {
                    val month = YearMonth.of(year, recurrence.anchorDate.monthValue)
                    yield(month.atDay(minOf(recurrence.anchorDate.dayOfMonth, month.lengthOfMonth())))
                    year++
                }
            }
        }
    }
}
