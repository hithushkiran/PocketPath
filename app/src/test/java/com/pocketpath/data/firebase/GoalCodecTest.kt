package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.calculation.goalEarmarkLkrMinor
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.Goal
import com.pocketpath.domain.model.ReservationEffect
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.CompleteLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GoalCodecTest {
    private val now = Instant.parse("2026-11-05T06:00:00Z")
    private val date = LocalDate.of(2026, 11, 5)
    private val audit = Timestamp(now.epochSecond, 0)

    @Test
    fun savedGoalKeepsItsScheduleAndHasNoEditableSavedBalance() {
        val goal = Goal("laptop", "Laptop", EntryAmount(Money.lkr(49_000_000)), date, date.plusYears(1),
            firstContributionDate = date.plusDays(5))
        val fields = FoundationCodec.goalData(goal)
        val saved = FoundationCodec.goal(goal.id, fields + mapOf("createdAt" to audit, "updatedAt" to audit))
        assertEquals(goal.firstContributionDate, saved.firstContributionDate)
        assertEquals(goal.target, saved.target)
        assertEquals(goal.deadline, saved.deadline)
        assertTrue("savedLkrMinor" !in fields)
    }

    @Test
    fun reservationRoundTripsWithoutAffectingBalanceOrSpending() {
        val reserve = FinancialTransaction(
            operationId = "reserve-laptop",
            kind = TransactionKind.GOAL_RESERVE,
            status = TransactionStatus.POSTED,
            financialTime = FinancialTime(now, date, ZoneId.of("Asia/Colombo")),
            clientCreatedAt = now,
            movements = emptyList(),
            reservationEffects = listOf(ReservationEffect("laptop", "bank", Money.lkr(2_000_000))),
        )
        val fields = FoundationCodec.eventData(reserve)
        val saved = FoundationCodec.event(reserve.id, fields + ("createdAt" to audit))
        assertTrue(saved.movements.isEmpty())
        assertTrue(saved.components.isEmpty())
        assertEquals(listOf("bank"), saved.accountIds)
        assertEquals(listOf("laptop"), saved.goalIds)
        assertEquals(2_000_000L, goalEarmarkLkrMinor(CompleteLedger(listOf(saved)), "laptop"))
    }
}
