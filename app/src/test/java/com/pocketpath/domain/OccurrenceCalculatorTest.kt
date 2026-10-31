package com.pocketpath.domain

import com.pocketpath.domain.calculation.OccurrenceCalculator
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.Recurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class OccurrenceCalculatorTest {
    private val colombo = ZoneId.of("Asia/Colombo")

    @Test fun weeklyIncomeOccurrencesUseAnchorActiveAndEndDates() {
        val schedule = schedule(
            recurrence = Recurrence(Frequency.WEEKLY, LocalDate.of(2026, 10, 5)),
            activeFrom = LocalDate.of(2026, 10, 12),
            endDate = LocalDate.of(2026, 10, 26),
        )

        val occurrences = OccurrenceCalculator.incomeReminderOccurrences(
            schedule,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 11, 2),
        )

        assertEquals(
            listOf("2026-10-12", "2026-10-19", "2026-10-26"),
            occurrences.map { it.nominalDate.toString() },
        )
        assertEquals(occurrences.map { "income_schedule_${it.nominalDate}" }, occurrences.map { it.id })
        assertEquals(occurrences.map { it.nominalDate }, occurrences.map { it.effectiveDueDate })
    }

    @Test fun monthlyAndYearlySchedulesUseTheirUnambiguousAnchorDay() {
        val monthly = schedule(Recurrence(Frequency.MONTHLY, LocalDate.of(2026, 1, 15)))
        val yearly = schedule(Recurrence(Frequency.YEARLY, LocalDate.of(2024, 10, 25)))

        assertEquals(
            listOf("2026-01-15", "2026-02-15", "2026-03-15"),
            OccurrenceCalculator.incomeReminderOccurrences(
                monthly, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
            ).map { it.nominalDate.toString() },
        )
        assertEquals(
            listOf("2025-10-25", "2026-10-25", "2027-10-25"),
            OccurrenceCalculator.incomeReminderOccurrences(
                yearly, LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31),
            ).map { it.nominalDate.toString() },
        )
    }

    @Test fun repeatedGenerationIsIdenticalAndRetainsScheduleTimezone() {
        val schedule = schedule(Recurrence(Frequency.WEEKLY, LocalDate.of(2026, 10, 5)))
        val start = LocalDate.of(2026, 10, 1)
        val end = LocalDate.of(2026, 10, 31)
        val originalDefault = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val first = OccurrenceCalculator.incomeReminderOccurrences(schedule, start, end)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val second = OccurrenceCalculator.incomeReminderOccurrences(schedule, start, end)

            assertEquals(first.map { it.id }, second.map { it.id })
            assertEquals(first.map { it.nominalDate }, second.map { it.nominalDate })
            assertEquals(listOf(colombo, colombo, colombo, colombo), first.map { it.timeZone })
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test fun incomeAndCommitmentScheduleIdsProduceDistinctOccurrenceIds() {
        val date = LocalDate.of(2026, 10, 25)

        assertNotEquals(
            StableIds.occurrence("income_schedule", date),
            StableIds.occurrence("commitment_schedule", date),
        )
    }

    @Test fun monthlyMonthEndAnchorsClampWithoutChangingTheScheduleAnchor() {
        val recurrence = Recurrence(Frequency.MONTHLY, LocalDate.of(2026, 1, 31))
        val occurrences = OccurrenceCalculator.incomeReminderOccurrences(
            schedule(recurrence), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30),
        )
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), occurrences.map { it.nominalDate.toString() })
        assertEquals(LocalDate.of(2026, 1, 31), recurrence.anchorDate)
        assertEquals(occurrences.map { it.id }.toSet().size, occurrences.size)
    }

    @Test fun yearlyLeapDayAnchorClampsOnlyInNonLeapYears() {
        val recurrence = Recurrence(Frequency.YEARLY, LocalDate.of(2024, 2, 29))
        val occurrences = OccurrenceCalculator.incomeReminderOccurrences(
            schedule(recurrence), LocalDate.of(2024, 1, 1), LocalDate.of(2028, 12, 31),
        )
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), occurrences.map { it.nominalDate.toString() })
        assertEquals(LocalDate.of(2024, 2, 29), recurrence.anchorDate)
        assertEquals(occurrences.map { it.id }.toSet().size, occurrences.size)
    }

    private fun schedule(
        recurrence: Recurrence,
        activeFrom: LocalDate = recurrence.anchorDate,
        endDate: LocalDate? = null,
    ) = Schedule(
        id = "income_schedule",
        kind = ScheduleKind.INCOME_REMINDER,
        title = "Salary",
        recurrence = recurrence,
        timeZone = colombo,
        expectedAmount = EntryAmount(Money.lkr(100_000)),
        amountMode = AmountMode.FIXED,
        activeFrom = activeFrom,
        endDate = endDate,
        incomeSourceId = "salary_source",
    )
}
