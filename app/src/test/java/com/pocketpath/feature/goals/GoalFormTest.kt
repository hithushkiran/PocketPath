package com.pocketpath.feature.goals

import com.pocketpath.domain.calculation.monthlyGoalDates
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GoalFormTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val form = GoalForm(
        name = " MacBook ",
        target = "490000",
        startDate = "2026-10-01",
        deadline = "2027-09-30",
        firstContributionDate = "2026-10-25",
    )

    @Test fun draftUsesTwelveExplicitDatesAndDoesNotAssumeExistingSavings() {
        val preview = (validateGoalForm(form, today) as GoalFormResult.Valid).preview
        assertEquals("MacBook", preview.name)
        assertEquals(49_000_000L, preview.targetLkrMinor)
        assertEquals(49_000_000L, preview.pace.remainingLkrMinor)
        val installments = preview.pace.installments
        assertEquals(12, installments.size)
        assertEquals(LocalDate.of(2026, 10, 25), installments.first().date)
        assertEquals(LocalDate.of(2027, 9, 25), installments.last().date)
        assertEquals(49_000_000L, installments.sumOf { it.amountLkrMinor })
        assertEquals(listOf(4_083_334L, 4_083_334L, 4_083_334L, 4_083_334L),
            installments.take(4).map { it.amountLkrMinor })
        assertTrue(installments.drop(4).all { it.amountLkrMinor == 4_083_333L })
    }

    @Test fun shortMonthsKeepOriginalAnchorAndRespectDeadline() {
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31"),
            monthlyGoalDates(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 31))
                .map { it.toString() },
        )
        assertEquals(
            listOf("2028-01-31", "2028-02-29"),
            monthlyGoalDates(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 30))
                .map { it.toString() },
        )
    }

    @Test fun invalidMoneyAndDatesProduceFieldErrorsWithoutRounding() {
        fun errors(value: GoalForm) = (validateGoalForm(value, today) as GoalFormResult.Invalid).errors
        for (amount in listOf("0", "-1", "1.001", "1.000", "1e3", "1,000", "1000000000.01")) {
            assertTrue("Accepted invalid amount $amount", GoalField.TARGET in errors(form.copy(target = amount)))
        }
        assertTrue(GoalField.NAME in errors(form.copy(name = " ")))
        assertTrue(GoalField.START_DATE in errors(form.copy(startDate = "2026-02-30")))
        assertTrue(GoalField.DEADLINE in errors(form.copy(deadline = "2026-09-30")))
        assertTrue(GoalField.FIRST_CONTRIBUTION_DATE in errors(form.copy(firstContributionDate = "2026-09-30")))
        assertTrue(GoalField.FIRST_CONTRIBUTION_DATE in errors(form.copy(firstContributionDate = "2027-10-01")))
        assertTrue(GoalField.FIRST_CONTRIBUTION_DATE in errors(form.copy(firstContributionDate = "2026-10-1")))
    }

    @Test fun exactBoundsAndSingleRemainingDateAreSupported() {
        val maximum = validateGoalForm(form.copy(target = "1000000000.00"), today)
        assertEquals(100_000_000_000L, (maximum as GoalFormResult.Valid).preview.targetLkrMinor)
        val single = form.copy(
            target = "0.01", deadline = "2026-10-01", firstContributionDate = "2026-10-01",
        )
        val installments = (validateGoalForm(single, today) as GoalFormResult.Valid).preview.pace.installments
        assertEquals(1, installments.size)
        assertEquals(1L, installments.single().amountLkrMinor)
        assertTrue(validateGoalForm(form, LocalDate.of(2026, 10, 26)) is GoalFormResult.Invalid)
    }
}
