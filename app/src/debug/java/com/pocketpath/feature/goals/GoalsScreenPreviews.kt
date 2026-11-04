package com.pocketpath.feature.goals

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.pocketpath.ui.theme.PocketPathTheme
import java.time.LocalDate

/** Debug-only sample. Never wired to production authentication or repositories. */
@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun GoalDraftPreviewSample() {
    val today = LocalDate.of(2026, 10, 1)
    val form = GoalForm("MacBook", "490000", "2026-10-01", "2027-09-30", "2026-10-25")
    val result = validateGoalForm(form, today) as GoalFormResult.Valid
    PocketPathTheme {
        GoalsScreen(GoalsUiState(userId = "preview-only", form = form, preview = result.preview))
    }
}
