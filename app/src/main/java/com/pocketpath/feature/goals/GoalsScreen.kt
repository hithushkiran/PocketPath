package com.pocketpath.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.lkrText

@Composable
fun GoalsScreen(
    state: GoalsUiState = GoalsUiState(),
    onEvent: (GoalsEvent) -> Unit = {},
) {
    val editable = state.userId != null
    val preview = state.preview

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Give your savings a purpose", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Text("Saved goals are not available yet. You can estimate a new plan below.")
            Text("Temporary draft only; cleared on sign-out or app restart.")
        }
        item {
            GoalDraftField(
                "Goal name", state.form.name, GoalField.NAME, state, onEvent,
                "For example, a laptop or an emergency fund.",
            )
        }
        item {
            GoalDraftField(
                "Target amount (LKR)", state.form.target, GoalField.TARGET, state, onEvent,
                "Positive amount; at most two decimal places, without commas.",
                KeyboardType.Decimal,
            )
        }
        item {
            GoalDraftField(
                "Goal start date", state.form.startDate, GoalField.START_DATE, state, onEvent,
                "yyyy-MM-dd",
            )
        }
        item {
            GoalDraftField(
                "Deadline", state.form.deadline, GoalField.DEADLINE, state, onEvent,
                "yyyy-MM-dd",
            )
        }
        item {
            GoalDraftField(
                "First remaining monthly contribution", state.form.firstContributionDate,
                GoalField.FIRST_CONTRIBUTION_DATE, state, onEvent,
                "yyyy-MM-dd. The same day is used monthly; short months use their last day.",
            )
        }
        item {
            Text("This estimate assumes no confirmed allocation. It does not inspect your accounts.")
            Button(
                onClick = { onEvent(GoalsEvent.PreviewDraft) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Preview goal plan")
            }
            TextButton(
                onClick = { onEvent(GoalsEvent.ClearDraft) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear draft")
            }
            if (state.errors.isNotEmpty()) {
                FinanceMessage("Review the highlighted fields to preview this plan.")
            }
        }
        if (preview != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Unsaved estimate", style = MaterialTheme.typography.labelLarge)
                        Text(preview.name, style = MaterialTheme.typography.titleLarge)
                        Text("Target: ${lkrText(preview.targetLkrMinor)}")
                        Text("Goal period: ${preview.startDate} to ${preview.deadline}")
                        Text("Assumed confirmed allocation: ${lkrText(0L)}")
                        Text("Remaining target: ${lkrText(preview.pace.remainingLkrMinor)}")
                        Text("Monthly contribution dates: ${preview.pace.installments.size}")
                        Text("No goal was saved and no money was reserved.")
                    }
                }
            }
            item {
                Text("Required contributions", style = MaterialTheme.typography.titleMedium)
                Text("Every date and amount used in this estimate is shown below.")
            }
            items(preview.pace.installments, key = { it.date.toString() }) { installment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(installment.date.toString(), modifier = Modifier.weight(1f))
                    Text(
                        lkrText(installment.amountLkrMinor),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun GoalDraftField(
    label: String,
    value: String,
    field: GoalField,
    state: GoalsUiState,
    onEvent: (GoalsEvent) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val error = state.errors[field]
    OutlinedTextField(
        value = value,
        onValueChange = { onEvent(GoalsEvent.Edit(field, it)) },
        label = { Text(label) },
        supportingText = { Text(error ?: hint) },
        isError = error != null,
        enabled = state.userId != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
