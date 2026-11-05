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
import androidx.compose.material3.AlertDialog
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
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.domain.model.Account
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.lkrText

@Composable
fun GoalsScreen(
    state: GoalsUiState = GoalsUiState(),
    onEvent: (GoalsEvent) -> Unit = {},
    finance: FinanceUiState = FinanceUiState(),
) {
    val editable = state.userId != null && !state.busy && !state.retryGoal
    val accounts = finance.selectableAccounts
    val reservations = finance.goalReservationsMinor.takeIf { finance.ready }
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
            if (state.goalsLoading) Text("Loading goals…")
            state.goalsError?.let {
                FinanceMessage(it)
                TextButton(onClick = { onEvent(GoalsEvent.ReloadGoals) }) { Text("Retry loading goals") }
            }
            val pending = state.contribution
            if (pending?.retryRequired == true && !state.showContribution) {
                TextButton(onClick = { onEvent(GoalsEvent.OpenContribution(pending.goalId, pending.accountId)) }) {
                    Text("Review pending contribution")
                }
            }
        }
        items(state.goals, key = { "goal-" + it.id }) { goal ->
            val reserved = reservations?.let { it[goal.id] ?: 0L }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(goal.name, style = MaterialTheme.typography.titleLarge)
                    Text("Target ${lkrText(goal.target.money.minor)} · Due ${goal.deadline}")
                    Text(if (reserved == null) "Confirmed progress is unavailable."
                        else "${lkrText(reserved)} reserved · ${lkrText(maxOf(0L, goal.target.money.minor - reserved))} remaining")
                    Button(
                        onClick = { onEvent(GoalsEvent.OpenContribution(goal.id, accounts.first().id)) },
                        enabled = editable && state.contribution == null && accounts.isNotEmpty() && reserved != null,
                    ) { Text("Add contribution") }
                }
            }
        }
        item { Text("New goal", style = MaterialTheme.typography.titleMedium) }
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
            Button(
                onClick = { onEvent(GoalsEvent.SaveGoal) },
                enabled = state.userId != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.retryGoal) "Retry saving goal" else "Save goal")
            }
            state.saveError?.let { FinanceMessage(it) }
            TextButton(
                onClick = { onEvent(GoalsEvent.ClearDraft) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear draft")
            }
            if (state.errors.isNotEmpty()) {
                FinanceMessage("Review the highlighted fields.")
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

    state.contribution?.takeIf { state.showContribution }?.let { form ->
        GoalContributionDialog(form, accounts, state.busy, onEvent)
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
        enabled = state.userId != null && !state.busy && !state.retryGoal,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GoalContributionDialog(
    form: GoalContributionForm,
    accounts: List<Account>,
    busy: Boolean,
    onEvent: (GoalsEvent) -> Unit,
) {
    val editable = !busy && !form.retryRequired
    AlertDialog(
        onDismissRequest = { onEvent(GoalsEvent.CloseContribution) },
        title = { Text("Reserve money for this goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceChoice(
                    label = "Backing account",
                    selectedId = form.accountId,
                    options = accounts.map { it.id to it.name },
                    enabled = editable,
                    onSelected = { onEvent(GoalsEvent.EditContribution(accountId = it)) },
                )
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEvent(GoalsEvent.EditContribution(amount = it)) },
                    label = { Text("Contribution (LKR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = editable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                form.error?.let { FinanceMessage(it) }
                if (form.retryRequired) Text("Retry checks the same contribution.")
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(GoalsEvent.SaveContribution) }, enabled = !busy) {
                Text(if (form.retryRequired) "Retry contribution" else "Save contribution")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(GoalsEvent.CloseContribution) }, enabled = !busy) {
                Text(if (form.retryRequired) "Close" else "Cancel")
            }
        },
    )
}
