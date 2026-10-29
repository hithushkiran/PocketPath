package com.pocketpath.feature.finance

import com.pocketpath.domain.model.Category
import com.pocketpath.domain.model.CommitmentClass
import org.junit.Assert.*
import org.junit.Test

class FinanceStateCategoryTest {

    @Test
    fun categoryFormStateUpdatesAndDialogTogglesCorrectly() {
        var state = FinanceUiState()
        assertFalse(state.showCategoryDialog)

        // Toggle dialog ON
        state = state.copy(showCategoryDialog = true)
        assertTrue(state.showCategoryDialog)

        // Update CategoryForm fields
        val updatedForm = state.categoryForm.copy(
            name = "Education",
            classification = CommitmentClass.UNPLANNED_ESSENTIAL,
            iconKey = "school",
        )
        state = state.copy(categoryForm = updatedForm)

        assertEquals("Education", state.categoryForm.name)
        assertEquals(CommitmentClass.UNPLANNED_ESSENTIAL, state.categoryForm.classification)
        assertEquals("school", state.categoryForm.iconKey)
        assertFalse(state.categoryForm.busy)
        assertNull(state.categoryForm.error)
    }

    @Test
    fun newCustomCategoryAppearsInSelectableCategories() {
        val initialCategory = Category("food", "Food", CommitmentClass.DISCRETIONARY)
        val customCategory = Category("education", "Education", CommitmentClass.UNPLANNED_ESSENTIAL)

        var state = FinanceUiState(categories = listOf(initialCategory))
        assertEquals(1, state.selectableCategories.size)

        state = state.copy(categories = listOf(initialCategory, customCategory))
        assertEquals(2, state.selectableCategories.size)
        assertTrue(state.selectableCategories.any { it.id == "education" && it.name == "Education" })
    }

    @Test
    fun duplicateCategoryNameIsDetectedCaseInsensitively() {
        val existingCategory = Category("food", "Food", CommitmentClass.DISCRETIONARY)
        val state = FinanceUiState(categories = listOf(existingCategory))

        val isDuplicate = state.selectableCategories.any { it.name.equals("FOOD", ignoreCase = true) }
        assertTrue(isDuplicate)

        val isNotDuplicate = state.selectableCategories.any { it.name.equals("Entertainment", ignoreCase = true) }
        assertFalse(isNotDuplicate)
    }
}
