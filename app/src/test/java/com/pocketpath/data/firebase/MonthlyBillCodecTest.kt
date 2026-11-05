package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.Recurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MonthlyBillCodecTest {
    private val date = LocalDate.of(2026, 11, 30)
    private val audit = Timestamp(1_791_000_000L, 0)
    private val bill = Schedule(
        id = "monthly-rent",
        kind = ScheduleKind.COMMITMENT,
        title = "Rent",
        recurrence = Recurrence(Frequency.MONTHLY, date),
        timeZone = ZoneId.of("Asia/Colombo"),
        expectedAmount = EntryAmount(Money.lkr(3_400_000)),
        amountMode = AmountMode.FIXED,
        activeFrom = date,
        categoryId = "housing",
        commitmentClass = CommitmentClass.COMMITTED,
    )

    @Test
    fun fixedMonthlyBillEncodesTheScheduleAndDecodesTheFirstDueOccurrence() {
        val (schedule, occurrence) = FoundationCodec.firstMonthlyBillData(bill)
        assertEquals("MONTHLY", schedule["frequency"])
        assertEquals("2026-11-30", schedule["anchorDate"])
        assertEquals(3_400_000L, schedule["expectedAmountMinor"])

        val id = StableIds.occurrence(bill.id, date)
        val saved = FoundationCodec.firstMonthlyBill(id, occurrence + auditFields())
        assertEquals(id, saved.id)
        assertEquals(date, saved.effectiveDueDate)
        assertEquals("Rent", saved.titleSnapshot)
        assertEquals("housing", saved.categoryId)
        assertEquals(bill.expectedAmount, saved.expectedAmount)
        assertEquals(CommitmentClass.COMMITTED, saved.commitmentClass)
        assertEquals(OccurrenceState.OPEN, saved.state)
    }

    @Test
    fun decoderRejectsTheWrongIdAndNonPositiveAmounts() {
        val fields = FoundationCodec.firstMonthlyBillData(bill).second + auditFields()
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.firstMonthlyBill("unrelated-occurrence", fields)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.firstMonthlyBill(StableIds.occurrence(bill.id, date), fields + ("expectedAmountMinor" to 0L))
        }
    }

    private fun auditFields() = mapOf("createdAt" to audit, "updatedAt" to audit)
}
