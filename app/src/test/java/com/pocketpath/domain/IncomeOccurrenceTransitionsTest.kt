package com.pocketpath.domain

import com.pocketpath.domain.calculation.IncomeOccurrenceTransitions
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.RecordMetadata
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeOccurrenceTransitionsTest {
    private fun occurrence(revision: Long = 0L) = Occurrence(
        scheduleId = "salary_monthly",
        nominalDate = LocalDate.of(2026, 10, 25),
        effectiveDueDate = LocalDate.of(2026, 10, 25),
        kind = ScheduleKind.INCOME_REMINDER,
        titleSnapshot = "Salary",
        expectedAmount = EntryAmount(Money.lkr(130_000_00L)),
        timeZone = ZoneId.of("Asia/Colombo"),
        sourceId = "salary",
        metadata = RecordMetadata(revision = revision),
    )

    @Test fun adjustChangesOnlyOpenOccurrenceAndAdvancesRevision() {
        val result = IncomeOccurrenceTransitions.adjust(
            occurrence(), 0L, EntryAmount(Money.lkr(128_000_00L)), LocalDate.of(2026, 10, 26),
        ) as IncomeOccurrenceTransitions.Decision.Apply

        assertEquals(128_000_00L, result.occurrence.expectedAmount.money.minor)
        assertEquals(LocalDate.of(2026, 10, 26), result.occurrence.effectiveDueDate)
        assertEquals(1L, result.occurrence.metadata.revision)
    }

    @Test fun skipAndSnoozeAreRevisionAwareAndDoNotPostIncome() {
        val snoozed = (IncomeOccurrenceTransitions.snooze(
            occurrence(), 0L, Instant.parse("2026-10-25T04:00:00Z"),
        ) as IncomeOccurrenceTransitions.Decision.Apply).occurrence
        assertEquals(OccurrenceState.OPEN, snoozed.state)
        assertEquals(1L, snoozed.metadata.revision)

        val skipped = IncomeOccurrenceTransitions.skip(snoozed, 1L) as IncomeOccurrenceTransitions.Decision.Apply
        assertEquals(OccurrenceState.SKIPPED, skipped.occurrence.state)
        assertEquals(null, skipped.occurrence.confirmedTransactionId)
    }

    @Test fun confirmationHasDeterministicReplayAndRejectsConflictingReceipt() {
        val confirmed = (IncomeOccurrenceTransitions.confirm(
            occurrence(), 0L, "receipt_salary_monthly_2026-10-25",
        ) as IncomeOccurrenceTransitions.Decision.Apply).occurrence
        assertEquals(OccurrenceState.CONFIRMED, confirmed.state)
        assertEquals("receipt_salary_monthly_2026-10-25", confirmed.confirmedTransactionId)

        assertTrue(IncomeOccurrenceTransitions.confirm(
            confirmed, 0L, "receipt_salary_monthly_2026-10-25",
        ) is IncomeOccurrenceTransitions.Decision.Replay)
        assertTrue(IncomeOccurrenceTransitions.confirm(
            confirmed, 0L, "other_receipt",
        ) is IncomeOccurrenceTransitions.Decision.Conflict)
    }
}
