package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.calculation.IncomeReminderAction
import com.pocketpath.domain.calculation.OccurrenceCalculator
import com.pocketpath.domain.calculation.scheduledIncomeReceipt
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.Recurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IncomeReminderCodecTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private val anchor = LocalDate.of(2026, 1, 31)
    private val nominal = LocalDate.of(2026, 2, 28)
    private val enteredAt = Instant.parse("2026-03-01T04:00:00Z")
    private val audit = Timestamp(enteredAt.epochSecond, 0)
    private val schedule = Schedule(
        "salary", ScheduleKind.INCOME_REMINDER, "Salary",
        Recurrence(Frequency.MONTHLY, anchor), zone,
        EntryAmount(Money.lkr(12_000_000L)), AmountMode.ESTIMATE, anchor,
        incomeSourceId = "salary-source", preferredAccountId = "bank",
    )
    private val prompt = OccurrenceCalculator.incomeReminderOccurrences(schedule, nominal, nominal).single()
    private val actual = EntryAmount(Money.lkr(13_500_000L))

    @Test
    fun scheduleAndAdjustedOccurrenceRetainNominalIdentity() {
        val stored = FoundationCodec.incomeReminderSchedule(
            schedule.id, FoundationCodec.incomeReminderScheduleData(schedule) + auditFields(),
        )
        assertEquals(anchor, stored.recurrence.anchorDate)
        assertEquals("bank", stored.preferredAccountId)
        val adjusted = prompt.copy(effectiveDueDate = nominal.plusDays(2), expectedAmount = actual)
        val data = FoundationCodec.incomeReminderOccurrenceData(adjusted) + auditFields()
        val decoded = FoundationCodec.incomeReminderOccurrence(prompt.id, data)
        assertEquals("salary_2026-02-28", decoded.id)
        assertEquals(LocalDate.of(2026, 3, 2), decoded.effectiveDueDate)
        assertEquals(actual, decoded.expectedAmount)
        assertEquals(OccurrenceState.OPEN, decoded.state)
        assertNull(decoded.confirmedTransactionId)
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.incomeReminderOccurrence("salary_2026-03-02", data)
        }
    }

    @Test
    fun confirmationEncodesOneActualIncomeMovementWithDeterministicLink() {
        val action = IncomeReminderAction.Confirm(actual, LocalDate.of(2026, 3, 1), enteredAt)
        val receipt = scheduledIncomeReceipt(schedule, prompt, action)
        val data = FoundationCodec.eventData(receipt) + ("createdAt" to audit)
        val decoded = FoundationCodec.event(receipt.id, data)
        assertEquals(StableIds.scheduledReceipt(prompt.id), decoded.id)
        assertEquals(prompt.id, decoded.occurrenceId)
        assertEquals(action.receivedOn, decoded.financialTime.occurredLocalDate)
        assertEquals("bank", decoded.movements.single().accountId)
        assertEquals(NativeAmount.Fiat(actual.money), decoded.movements.single().amount)
        assertEquals(listOf("salary-source"), decoded.incomeSourceIds)
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.event(receipt.id, data + ("expectedPaymentId" to "another-payment"))
        }
    }

    @Test
    fun recurrenceWindowDoesNotReturnLaterDatesInTheSameMonthOrYear() {
        val cutoff = nominal.minusDays(1)
        assertEquals(emptyList<Any>(), OccurrenceCalculator.incomeReminderOccurrences(schedule, cutoff, cutoff))
        val annual = schedule.copy(recurrence = Recurrence(Frequency.YEARLY, anchor))
        val beforeNextAnnualDate = LocalDate.of(2027, 1, 15)
        assertEquals(emptyList<Any>(), OccurrenceCalculator.incomeReminderOccurrences(
            annual, beforeNextAnnualDate, beforeNextAnnualDate,
        ))
    }

    private fun auditFields() = mapOf("createdAt" to audit, "updatedAt" to audit)
}
