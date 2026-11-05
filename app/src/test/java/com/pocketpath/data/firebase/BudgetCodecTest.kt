package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.Money
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetCodecTest {

    private val audit = Timestamp(1_791_000_000L, 0)

    @Test
    fun budgetDataEncodesOverallDiscretionaryBudgetCorrectly() {
        val budget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val encoded = FoundationCodec.budgetData(budget)

        assertEquals("OVERALL_DISCRETIONARY", encoded["scope"])
        assertEquals("MONTHLY", encoded["periodType"])
        assertEquals(50000_00L, encoded["limitLkrMinor"])
        assertEquals("2026-10-01", encoded["effectiveFrom"])
        assertEquals(false, encoded["rolloverEnabled"])
        assertEquals("ACTIVE", encoded["state"])
        assertEquals(0L, encoded["revision"])
        assertNull(encoded["categoryId"])
        assertNull(encoded["effectiveTo"])
    }

    @Test
    fun budgetDataEncodesCategoryDiscretionaryBudgetWithCategoryId() {
        val budget = Budget(
            id = "cat_b1",
            scope = BudgetScope.CATEGORY_DISCRETIONARY,
            periodType = BudgetPeriod.WEEKLY,
            limit = EntryAmount(Money.lkr(15000_00)),
            categoryId = "dining",
            effectiveFrom = LocalDate.of(2026, 10, 1),
            effectiveTo = LocalDate.of(2026, 12, 31),
        )

        val encoded = FoundationCodec.budgetData(budget)

        assertEquals("CATEGORY_DISCRETIONARY", encoded["scope"])
        assertEquals("WEEKLY", encoded["periodType"])
        assertEquals(15000_00L, encoded["limitLkrMinor"])
        assertEquals("dining", encoded["categoryId"])
        assertEquals("2026-12-31", encoded["effectiveTo"])
    }

    @Test
    fun budgetDecodesFirestoreFieldsCorrectly() {
        val fields = mapOf(
            "scope" to "OVERALL_DISCRETIONARY",
            "periodType" to "MONTHLY",
            "limitLkrMinor" to 40000_00L,
            "effectiveFrom" to "2026-10-01",
            "rolloverEnabled" to false,
            "state" to "ACTIVE",
            "createdAt" to audit,
            "updatedAt" to audit,
            "revision" to 0L,
        )

        val decoded = FoundationCodec.budget("overall_b1", fields)

        assertEquals("overall_b1", decoded.id)
        assertEquals(BudgetScope.OVERALL_DISCRETIONARY, decoded.scope)
        assertEquals(BudgetPeriod.MONTHLY, decoded.periodType)
        assertEquals(40000_00L, decoded.limit.money.minor)
        assertEquals(LocalDate.of(2026, 10, 1), decoded.effectiveFrom)
        assertFalse(decoded.rolloverEnabled)
        assertEquals(BudgetState.ACTIVE, decoded.state)
        assertNull(decoded.categoryId)
    }

    @Test
    fun sameBudgetComparesFieldsIgnoringAuditTimestamps() {
        val budget = Budget(
            id = "overall_b1",
            scope = BudgetScope.OVERALL_DISCRETIONARY,
            periodType = BudgetPeriod.MONTHLY,
            limit = EntryAmount(Money.lkr(50000_00)),
            effectiveFrom = LocalDate.of(2026, 10, 1),
        )

        val first = FoundationCodec.budgetData(budget) + mapOf(
            "createdAt" to audit,
            "updatedAt" to audit,
        )

        val retry = FoundationCodec.budgetData(budget) + mapOf(
            "createdAt" to Timestamp(audit.seconds + 60, 0),
            "updatedAt" to Timestamp(audit.seconds + 120, 0),
        )

        assertTrue(FoundationCodec.sameBudget(first, retry))
    }

    @Test
    fun invalidBudgetFieldsOrCategoryMismatchAreRejected() {
        // Category discretionary without categoryId must be rejected
        val badCategoryBudget = mapOf(
            "scope" to "CATEGORY_DISCRETIONARY",
            "periodType" to "MONTHLY",
            "limitLkrMinor" to 10000_00L,
            "effectiveFrom" to "2026-10-01",
            "rolloverEnabled" to false,
            "state" to "ACTIVE",
            "createdAt" to audit,
            "updatedAt" to audit,
            "revision" to 0L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.budget("bad_b1", badCategoryBudget)
        }

        // Non-positive limit must be rejected
        val badLimitBudget = mapOf(
            "scope" to "OVERALL_DISCRETIONARY",
            "periodType" to "MONTHLY",
            "limitLkrMinor" to 0L,
            "effectiveFrom" to "2026-10-01",
            "rolloverEnabled" to false,
            "state" to "ACTIVE",
            "createdAt" to audit,
            "updatedAt" to audit,
            "revision" to 0L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.budget("bad_b2", badLimitBudget)
        }
    }
}
