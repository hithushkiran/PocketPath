package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.RecordMetadata
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.money.EntryAmount
import java.time.Instant
import java.time.LocalDate

/**
 * Pure, revision-aware transitions for an income reminder occurrence.
 *
 * Persistence is deliberately outside this class.  A repository must perform the corresponding
 * read/compare/write transaction and couple confirmation to the one deterministic ledger event.
 */
object IncomeOccurrenceTransitions {
    sealed interface Decision {
        data class Apply(val occurrence: Occurrence) : Decision
        data object Replay : Decision
        data object StaleRevision : Decision
        data object InvalidState : Decision
        data object Conflict : Decision
    }

    fun adjust(
        occurrence: Occurrence,
        expectedRevision: Long,
        amount: EntryAmount,
        effectiveDueDate: LocalDate,
    ): Decision {
        if (!isIncome(occurrence)) return Decision.InvalidState
        if (occurrence.metadata.revision != expectedRevision) {
            return if (occurrence.state == OccurrenceState.OPEN &&
                occurrence.expectedAmount == amount && occurrence.effectiveDueDate == effectiveDueDate
            ) Decision.Replay else Decision.StaleRevision
        }
        if (occurrence.state != OccurrenceState.OPEN) return Decision.InvalidState
        return Decision.Apply(occurrence.copy(
            expectedAmount = amount,
            effectiveDueDate = effectiveDueDate,
            snoozedUntil = null,
            metadata = nextMetadata(occurrence),
        ))
    }

    fun skip(occurrence: Occurrence, expectedRevision: Long): Decision {
        if (!isIncome(occurrence)) return Decision.InvalidState
        if (occurrence.metadata.revision != expectedRevision) {
            return if (occurrence.state == OccurrenceState.SKIPPED) Decision.Replay else Decision.StaleRevision
        }
        if (occurrence.state != OccurrenceState.OPEN) return Decision.InvalidState
        return Decision.Apply(occurrence.copy(
            state = OccurrenceState.SKIPPED,
            snoozedUntil = null,
            metadata = nextMetadata(occurrence),
        ))
    }

    fun snooze(occurrence: Occurrence, expectedRevision: Long, until: Instant): Decision {
        if (!isIncome(occurrence)) return Decision.InvalidState
        if (occurrence.metadata.revision != expectedRevision) {
            return if (occurrence.state == OccurrenceState.OPEN && occurrence.snoozedUntil == until) {
                Decision.Replay
            } else {
                Decision.StaleRevision
            }
        }
        if (occurrence.state != OccurrenceState.OPEN) return Decision.InvalidState
        return Decision.Apply(occurrence.copy(snoozedUntil = until, metadata = nextMetadata(occurrence)))
    }

    fun confirm(occurrence: Occurrence, expectedRevision: Long, transactionId: String): Decision {
        if (!isIncome(occurrence)) return Decision.InvalidState
        if (occurrence.metadata.revision != expectedRevision) {
            return if (occurrence.state == OccurrenceState.CONFIRMED && occurrence.confirmedTransactionId == transactionId) {
                Decision.Replay
            } else if (occurrence.state == OccurrenceState.CONFIRMED) {
                Decision.Conflict
            } else {
                Decision.StaleRevision
            }
        }
        if (occurrence.state != OccurrenceState.OPEN) return Decision.InvalidState
        return Decision.Apply(occurrence.copy(
            state = OccurrenceState.CONFIRMED,
            snoozedUntil = null,
            confirmedTransactionId = transactionId,
            metadata = nextMetadata(occurrence),
        ))
    }

    private fun isIncome(occurrence: Occurrence) = occurrence.kind == ScheduleKind.INCOME_REMINDER

    private fun nextMetadata(occurrence: Occurrence) = occurrence.metadata.copy(
        revision = Math.addExact(occurrence.metadata.revision, 1L),
    )
}
