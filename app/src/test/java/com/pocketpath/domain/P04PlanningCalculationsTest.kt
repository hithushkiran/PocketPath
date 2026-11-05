package com.pocketpath.domain

import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.LedgerScope
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class P04PlanningCalculationsTest {
    private val date = LocalDate.of(2026, 10, 25)
    private val zone = ZoneId.of("Asia/Colombo")
    private val now = date.atTime(12, 0).atZone(zone).toInstant()
    private val goal = Goal(
        "macbook", "MacBook", EntryAmount(Money.lkr(49_000_000L)),
        LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30),
    )
    private val plan = GoalPlan(
        goal.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
        Money.lkr(3_990_000L),
    )

    private fun reservation(
        id: String,
        minor: Long,
        planId: String? = null,
        at: Instant = now,
        voided: Boolean = false,
        accountId: String = "bank",
    ): FinancialTransaction = FinancialTransaction(
        operationId = id,
        kind = if (minor > 0L) TransactionKind.GOAL_RESERVE else TransactionKind.GOAL_RELEASE,
        status = if (voided) TransactionStatus.VOIDED else TransactionStatus.POSTED,
        financialTime = FinancialTime(at, at.atZone(zone).toLocalDate(), zone),
        clientCreatedAt = at,
        movements = emptyList(),
        reservationEffects = listOf(ReservationEffect(goal.id, accountId, Money.lkr(minor))),
        goalPlanId = planId,
        voidMetadata = if (voided) VoidMetadata(at, "Test correction") else null,
    )

    @Test fun goalProgressUsesEffectiveReservationsAndKeepsPlansSeparate() {
        val ledger = CompleteLedger(listOf(
            reservation("opening-allocation", 1_120_000L, accountId = "savings"),
            reservation("contribution", 2_000_000L, plan.id),
            reservation("voided", 100_000L, voided = true),
            reservation("future", 500_000L, at = now.plusSeconds(86400)),
        ))
        val result = calculateGoalProgress(goal, ledger, now, plan)
        assertEquals(3_120_000L, result.reservedLkrMinor)
        assertEquals(45_880_000L, result.remainingLkrMinor)
        assertEquals(2_000_000L, result.netPlanContributionLkrMinor!!)
        assertEquals(1_990_000L, result.remainingPlannedLkrMinor!!)
        assertEquals(1_120_000L, result.reservationsByAccountLkrMinor.getValue("savings"))

        val released = CompleteLedger(ledger.events + reservation("release", -500_000L, plan.id))
        val afterRelease = calculateGoalProgress(goal, released, now, plan)
        assertEquals(2_620_000L, afterRelease.reservedLkrMinor)
        assertEquals(2_490_000L, afterRelease.remainingPlannedLkrMinor!!)
        assertNull(calculateGoalProgress(goal, ledger, now).remainingPlannedLkrMinor)
        assertThrows(IllegalArgumentException::class.java) {
            calculateGoalProgress(goal, CompleteLedger(ledger.events, LedgerScope.AccountEvents("bank")), now)
        }
    }

    @Test fun savingsPaceUsesExplicitDatesAndDistributesEveryCent() {
        val dates = (0L..11L).map { date.plusMonths(it) }
        val pace = calculateSavingsPace(goal, 1_120_000L, date, dates)
        assertEquals(SavingsPaceStatus.SCHEDULED, pace.status)
        assertEquals(47_880_000L, pace.remainingLkrMinor)
        assertEquals(12, pace.installments.size)
        assertTrue(pace.installments.all { it.amountLkrMinor == 3_990_000L })

        val small = goal.copy(target = EntryAmount(Money.lkr(10_000L)))
        val split = calculateSavingsPace(small, 0L, date, dates.take(3))
        assertEquals(listOf(3334L, 3333L, 3333L), split.installments.map { it.amountLkrMinor })
        assertEquals(10_000L, split.installments.sumOf { it.amountLkrMinor })
        assertEquals(SavingsPaceStatus.REPLAN_REQUIRED, calculateSavingsPace(goal, 0L, date, emptyList()).status)
        assertEquals(SavingsPaceStatus.REPLAN_REQUIRED, calculateSavingsPace(goal, 0L, goal.deadline.plusDays(1), emptyList()).status)
        assertEquals(SavingsPaceStatus.TARGET_REACHED, calculateSavingsPace(goal, 49_000_000L, date, emptyList()).status)
        assertEquals(SavingsPaceStatus.INACTIVE, calculateSavingsPace(goal.copy(state = GoalState.PAUSED), 0L, date, dates).status)
        assertThrows(IllegalArgumentException::class.java) {
            calculateSavingsPace(goal, 0L, date, listOf(date, date))
        }
    }

    @Test fun payingBillsAndConfirmingPlannedSavingsDoNotDeductTwice() {
        val input = FundsPlanningInput(date, 8_000_000L, 1_000_000L, 3_000_000L, 2_000_000L)
        fun flexible(value: FundsPlanningInput) = (calculateFundsPlan(value) as FundsPlanningResult.Ready).flexibleFundsMinor
        assertEquals(2_000_000L, flexible(input))
        assertEquals(2_000_000L, flexible(input.copy(eligibleBalanceMinor = 7_000_000L, outstandingCommitmentsMinor = 2_000_000L)))
        assertEquals(2_000_000L, flexible(input.copy(reservedInEligibleAccountsMinor = 1_500_000L, remainingPlannedContributionsMinor = 1_500_000L)))
        // A contribution transferred into an excluded savings account reduces L and P.
        assertEquals(2_000_000L, flexible(input.copy(eligibleBalanceMinor = 7_500_000L, remainingPlannedContributionsMinor = 1_500_000L)))
        assertEquals(1_700_000L, flexible(input.copy(eligibleBalanceMinor = 7_700_000L)))
    }

    @Test fun unavailableInputsAreDifferentFromKnownZeroAndNegativeFundsRemainVisible() {
        val unknown = FundsPlanningInput(date, 100_000L, 0L, null, null)
        val result = calculateFundsPlan(unknown) as FundsPlanningResult.Unavailable
        assertEquals(setOf(MissingFundsInput.COMMITMENTS, MissingFundsInput.GOAL_PLANS), result.missing)
        val known = unknown.copy(outstandingCommitmentsMinor = 0L, remainingPlannedContributionsMinor = 0L)
        assertEquals(100_000L, (calculateFundsPlan(known) as FundsPlanningResult.Ready).flexibleFundsMinor)
        val negative = known.copy(eligibleBalanceMinor = -50_000L)
        assertEquals(-50_000L, (calculateFundsPlan(negative) as FundsPlanningResult.Ready).flexibleFundsMinor)
        assertThrows(IllegalArgumentException::class.java) {
            previewPurchaseImpact(unknown, EntryAmount(Money.lkr(1L)))
        }
    }

    @Test fun previewShowsIncrementalGapAndReleasesOnlyAnExistingBillReservation() {
        val input = FundsPlanningInput(date, 4_000_000L, 0L, 1_000_000L, 2_000_000L)
        val purchase = EntryAmount(Money.lkr(1_500_000L))
        val preview = previewPurchaseImpact(input, purchase)
        assertEquals(1_000_000L, preview.before.flexibleFundsMinor)
        assertEquals(-500_000L, preview.after.flexibleFundsMinor)
        assertEquals(500_000L, preview.incrementalPlanningGapMinor)
        assertEquals(4_000_000L, input.eligibleBalanceMinor!!)

        val bill = previewPurchaseImpact(input, EntryAmount(Money.lkr(500_000L)), 500_000L)
        assertEquals(bill.before.flexibleFundsMinor, bill.after.flexibleFundsMinor)
        assertEquals(0L, bill.incrementalPlanningGapMinor)
        val alreadyShort = input.copy(eligibleBalanceMinor = 2_500_000L)
        assertEquals(100_000L, previewPurchaseImpact(alreadyShort, EntryAmount(Money.lkr(100_000L))).incrementalPlanningGapMinor)
        assertThrows(IllegalArgumentException::class.java) {
            previewPurchaseImpact(input, EntryAmount(Money.lkr(100_000L)), 200_000L)
        }
    }

    @Test fun invalidReservationHistoryAndAggregateOverflowFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateGoalProgress(goal, CompleteLedger(listOf(reservation("bad-release", -1L))), now)
        }
        assertThrows(ArithmeticException::class.java) {
            calculateFundsPlan(FundsPlanningInput(date, Long.MIN_VALUE, 1L, 0L, 0L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            previewPurchaseImpact(
                FundsPlanningInput(date, 1_000L, 0L, 0L, 0L),
                EntryAmount(Money(FiatCurrency.USD, 100L)),
            )
        }
    }
}
