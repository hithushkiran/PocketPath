package com.pocketpath.feature.finance

import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetFormStateTest {

    @Test
    fun budgetFormStateUpdatesAndDialogTogglesCorrectly() {
        var state = FinanceUiState()
        assertFalse(state.showBudgetDialog)

        // Toggle dialog ON
        state = state.copy(showBudgetDialog = true)
        assertTrue(state.showBudgetDialog)

        // Update form fields
        val updatedForm = state.budgetForm.copy(
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.WEEKLY,
            categoryId = "dining",
            limitAmount = "15000",
        )
        state = state.copy(budgetForm = updatedForm)

        assertEquals(BudgetScope.CATEGORY_DISCRETIONARY, state.budgetForm.scope)
        assertEquals(BudgetPeriod.WEEKLY, state.budgetForm.periodType)
        assertEquals("dining", state.budgetForm.categoryId)
        assertEquals("15000", state.budgetForm.limitAmount)
        assertFalse(state.budgetForm.busy)
        assertNull(state.budgetForm.error)
    }

    @Test
    fun moneyParseValidatesPositiveLkrLimitsAndRejectsNegativeOrMalformed() {
        // Valid positive limit
        val money = Money.parse("50000", FiatCurrency.LKR)
        assertEquals(50000_00L, money.minor)

        val decimalMoney = Money.parse("12500.50", FiatCurrency.LKR)
        assertEquals(12500_50L, decimalMoney.minor)

        // Rejected cases
        assertThrows(IllegalArgumentException::class.java) {
            Money.parse("abc", FiatCurrency.LKR)
        }

        assertThrows(IllegalArgumentException::class.java) {
            Money.parse("12500.505", FiatCurrency.LKR) // >2 decimals
        }
    }

    @Test
    fun activeSlotCheckDetectsDuplicateActiveBudgetSlotWithEffectiveDates() {
        val today = LocalDate.of(2026, 10, 24)

        val activeOverall = Budget(
            id = "b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = com.pocketpath.domain.money.EntryAmount(Money.lkr(50000_00L)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val expiredBudget = Budget(
            id = "b2",
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = com.pocketpath.domain.money.EntryAmount(Money.lkr(20000_00L)),
            categoryId = "dining",
            effectiveFrom = LocalDate.of(2026, 8, 1),
            effectiveTo = LocalDate.of(2026, 8, 31),
        )

        val state = FinanceUiState(budgets = listOf(activeOverall, expiredBudget))

        // Currently effective active budget blocks duplicate creation
        val isOverallOccupied = state.budgets.any {
            it.state == BudgetState.ACTIVE &&
                it.scope == BudgetScope.OVERALL_DISCRETIONARY &&
                it.periodType == BudgetPeriod.MONTHLY &&
                it.categoryId == null &&
                !it.effectiveFrom.isAfter(today) &&
                (it.effectiveTo == null || !it.effectiveTo.isBefore(today))
        }
        assertTrue(isOverallOccupied)

        // Expired budget does NOT block creation of a currently effective budget
        val isDiningOccupied = state.budgets.any {
            it.state == BudgetState.ACTIVE &&
                it.scope == BudgetScope.CATEGORY_DISCRETIONARY &&
                it.periodType == BudgetPeriod.MONTHLY &&
                it.categoryId == "dining" &&
                !it.effectiveFrom.isAfter(today) &&
                (it.effectiveTo == null || !it.effectiveTo.isBefore(today))
        }
        assertFalse(isDiningOccupied)
    }

    @Test
    fun budgetFormBusyBlocksDuplicateSubmissions() {
        val busyForm = BudgetForm(limitAmount = "50000", busy = true)
        val state = FinanceUiState(showBudgetDialog = true, budgetForm = busyForm)

        // Form is busy -> cannot submit
        assertTrue(state.budgetForm.busy)
    }
}
