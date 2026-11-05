package com.pocketpath.domain

import com.pocketpath.data.sample.InMemoryFinanceRepository
import com.pocketpath.data.sample.SampleFixtures
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

class CategoryAndBudgetRepositoryTest {

    private val time = SampleFixtures.time
    private fun repo() = InMemoryFinanceRepository(
        userId = "test-user-p03",
        clock = Clock.fixed(time.occurredAt, time.recordedTimeZone),
        initialCategories = listOf(SampleFixtures.food),
    )

    @Test
    fun saveCategoryPersistsCustomCategoryAndAllowsReplay() = runBlocking {
        val repository = repo()
        val customCat = Category(
            id = "education",
            name = "Education",
            defaultCommitmentClass = CommitmentClass.UNPLANNED_ESSENTIAL,
            sortOrder = 10,
        )

        val result1 = repository.saveCategory("op_cat_1", customCat)
        assertTrue(result1 is CommandResult.Accepted)
        assertFalse((result1 as CommandResult.Accepted).replayed)

        // Idempotent replay with same operationId & category
        val result2 = repository.saveCategory("op_cat_1", customCat)
        assertTrue(result2 is CommandResult.Accepted)
        assertTrue((result2 as CommandResult.Accepted).replayed)

        val observed = (repository.observeCategories().first() as Observation.Data).value
        assertEquals(2, observed.size)
        assertTrue(observed.any { it.id == "education" && it.name == "Education" })
    }

    @Test
    fun saveCategoryWithConflictingOperationIdIsRejected() = runBlocking {
        val repository = repo()
        val cat1 = Category("cat1", "Cat One", CommitmentClass.DISCRETIONARY)
        val cat2 = Category("cat2", "Cat Two", CommitmentClass.COMMITTED)

        assertTrue(repository.saveCategory("op_reuse", cat1) is CommandResult.Accepted)

        val conflicting = repository.saveCategory("op_reuse", cat2) as CommandResult.Rejected
        assertEquals(FailureCode.CONFLICT, conflicting.failure.code)
    }

    @Test
    fun saveBudgetPersistsOverallAndCategoryBudgets() = runBlocking {
        val repository = repo()

        val overallBudget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val result1 = repository.saveBudget("op_b1", overallBudget)
        assertTrue(result1 is CommandResult.Accepted)

        val categoryBudget = Budget(
            id = "cat_b1",
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.WEEKLY,
            limit = EntryAmount(Money.lkr(10000_00)),
            categoryId = SampleFixtures.food.id,
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val result2 = repository.saveBudget("op_b2", categoryBudget)
        assertTrue(result2 is CommandResult.Accepted)

        val observedBudgets = (repository.observeBudgets().first() as Observation.Data).value
        assertEquals(2, observedBudgets.size)
    }

    @Test
    fun saveBudgetValidatesCategoryDiscretionaryRequiresCategoryId() {
        assertThrows(IllegalArgumentException::class.java) {
            Budget(
                id = "invalid_b1",
                scope = BudgetScope.CATEGORY_DISCRETIONARY,
                periodType = BudgetPeriod.MONTHLY,
                limit = EntryAmount(Money.lkr(10000_00)),
                categoryId = null, // Invalid for CATEGORY_DISCRETIONARY
                effectiveFrom = LocalDate.of(2026, 10, 1),
            )
        }
    }
}
