package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.calculation.OccurrenceCalculator
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.Recurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.NativeAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
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
        val monthlyBill = FoundationCodec.monthlyBill(id, occurrence + auditFields())
        assertEquals(0L, monthlyBill.paidMinor)
        assertEquals(3_400_000L, monthlyBill.remainingMinor)
        val saved = monthlyBill.occurrence
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
            FoundationCodec.monthlyBill("unrelated-occurrence", fields)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.monthlyBill(StableIds.occurrence(bill.id, date), fields + ("expectedAmountMinor" to 0L))
        }
    }

    @Test
    fun updatedBillKeepsTheOriginalSnapshotAndReportsExactRemainder() {
        val id = StableIds.occurrence(bill.id, date)
        val fields = FoundationCodec.firstMonthlyBillData(bill).second + auditFields() + mapOf(
            "revision" to 1L,
            "paidAmountMinor" to 1_000_000L,
            "lastBillExpenseId" to "payment-1",
        )
        val saved = FoundationCodec.monthlyBill(id, fields)
        assertEquals(id, saved.occurrence.id)
        assertEquals(1L, saved.occurrence.metadata.revision)
        assertEquals(2_400_000L, saved.remainingMinor)
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.monthlyBill(id, fields + ("paidAmountMinor" to 3_400_001L))
        }
    }

    @Test
    fun monthlyClampingKeepsTheOriginalDayAfterFebruary() {
        val anchor = LocalDate.of(2027, 1, 31)
        assertEquals(LocalDate.of(2027, 2, 28), OccurrenceCalculator.monthlyDate(anchor, YearMonth.of(2027, 2)))
        assertEquals(LocalDate.of(2027, 3, 31), OccurrenceCalculator.monthlyDate(anchor, YearMonth.of(2027, 3)))
    }

    @Test
    fun billExpenseRoundTripsAsAnOrdinaryLedgerMovementWithItsOccurrenceLink() {
        val occurrenceId = StableIds.occurrence(bill.id, date)
        val instant = date.atStartOfDay(bill.timeZone).toInstant()
        val expense = FinancialTransaction(
            operationId = "payment-1",
            kind = TransactionKind.EXPENSE,
            status = TransactionStatus.POSTED,
            financialTime = FinancialTime(instant, date, bill.timeZone),
            clientCreatedAt = instant,
            movements = listOf(AccountMovement("cash", NativeAmount.Fiat(Money.lkr(-1_000_000)))),
            components = listOf(ReportComponent(
                ComponentNature.SPENDING,
                KnownLkrAmount.Known(Money.lkr(1_000_000)),
                categoryId = bill.categoryId,
                commitmentClass = CommitmentClass.COMMITTED,
            )),
            occurrenceId = occurrenceId,
        )
        val saved = FoundationCodec.event(expense.id, FoundationCodec.eventData(expense) + ("createdAt" to audit))
        assertEquals(occurrenceId, saved.occurrenceId)
        assertEquals(expense.movements, saved.movements)
        assertEquals(expense.components, saved.components)
    }

    @Test
    fun estimateAndActualRoundTripWithoutChangingNextMonthsSnapshot() {
        val estimate = bill.copy(amountMode = AmountMode.ESTIMATE, expectedAmount = EntryAmount(Money.lkr(800_000)))
        val (schedule, first) = FoundationCodec.firstMonthlyBillData(estimate)
        val id = StableIds.occurrence(bill.id, date)
        val initial = FoundationCodec.monthlyBill(id, first + auditFields())
        assertEquals("ESTIMATE", schedule["amountMode"])
        assertEquals(800_000L, initial.remainingMinor)
        val patch = FoundationCodec.monthlyBillActualData(initial, EntryAmount(Money.lkr(950_000)))
        assertEquals(setOf("actualAmountMinor", "updatedAt", "revision"), patch.keys)
        val actual = FoundationCodec.monthlyBill(id, first + patch + auditFields())
        assertEquals(950_000L, actual.remainingMinor)
        assertEquals(800_000L, actual.occurrence.expectedAmount.money.minor)
        val paid = FoundationCodec.monthlyBill(id, first + patch + auditFields() + mapOf(
            "paidAmountMinor" to 950_000L, "lastBillExpenseId" to "payment", "revision" to 2L,
        ))
        assertEquals(0L, paid.remainingMinor)
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.monthlyBillActualData(paid, EntryAmount(Money.lkr(949_999)))
        }
        val nextDate = date.plusMonths(1)
        val next = initial.occurrence.copy(nominalDate = nextDate, effectiveDueDate = nextDate)
        val nextBill = FoundationCodec.monthlyBill(next.id, FoundationCodec.monthlyBillOccurrenceData(next) + auditFields())
        assertEquals(800_000L, nextBill.remainingMinor)
        assertEquals(null, nextBill.actualAmount)
        assertEquals(AmountMode.ESTIMATE, nextBill.occurrence.amountMode)
    }

    @Test
    fun fixedBillsCannotAcquireActualAmountsAndActualCannotUnderstatePaidHistory() {
        val first = FoundationCodec.firstMonthlyBillData(bill).second + auditFields()
        val fixed = FoundationCodec.monthlyBill(StableIds.occurrence(bill.id, date), first)
        assertEquals(AmountMode.FIXED, fixed.occurrence.amountMode)
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.monthlyBillActualData(fixed, EntryAmount(Money.lkr(950_000)))
        }
        for (invalid in listOf(
            first + mapOf("actualAmountMinor" to 950_000L, "revision" to 1L),
            first + mapOf("amountMode" to "ESTIMATE", "actualAmountMinor" to 0L, "revision" to 1L),
            first + mapOf("amountMode" to "ESTIMATE", "actualAmountMinor" to 950_000L, "revision" to 1L,
                "paidAmountMinor" to 950_001L, "lastBillExpenseId" to "payment"),
        )) assertThrows(IllegalArgumentException::class.java) { FoundationCodec.monthlyBill(fixed.occurrence.id, invalid) }
    }

    private fun auditFields() = mapOf("createdAt" to audit, "updatedAt" to audit)
}
