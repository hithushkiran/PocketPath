package com.pocketpath.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.SavedGoalProgress
import com.pocketpath.domain.calculation.SavingsPaceStatus
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.domain.model.Account
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.FormActions
import com.pocketpath.ui.components.SectionTabs
import com.pocketpath.ui.components.lkrText

@Composable
fun GoalsScreen(
    state: GoalsUiState = GoalsUiState(),
    onEvent: (GoalsEvent) -> Unit = {},
    finance: FinanceUiState = FinanceUiState(),
) {
    var showDraft by rememberSaveable { mutableStateOf(state.preview != null || state.retryGoal) }
    var showPreview by rememberSaveable { mutableStateOf(state.preview != null) }
    val accounts = finance.selectableAccounts

    Column(Modifier.fillMaxSize()) {
        SectionTabs(
            labels = listOf("My goals", "New goal"),
            selectedIndex = if (showDraft) 1 else 0,
            onSelect = { showDraft = it == 1 },
        )

        if (showDraft) {
            NewGoalForm(state, onEvent, onPreview = {
                showPreview = true
                onEvent(GoalsEvent.PreviewDraft)
            })
        } else {
            SavedGoals(state, finance, onEvent, onNewGoal = { showDraft = true })
        }
    }

    if (showDraft && showPreview) {
        state.preview?.let { preview ->
            GoalPlanPreview(preview, onDismiss = { showPreview = false })
        }
    }
    state.contribution?.takeIf { state.showContribution }?.let { form ->
        GoalContributionDialog(form, accounts, state.busy, onEvent)
    }
}

@Composable
private fun SavedGoals(
    state: GoalsUiState,
    finance: FinanceUiState,
    onEvent: (GoalsEvent) -> Unit,
    onNewGoal: () -> Unit,
) {
    val accounts = finance.selectableAccounts
    val editable = state.userId != null && !state.busy && !state.retryGoal
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.goalsLoading || state.goalsError != null || state.retryGoal ||
            state.contribution?.retryRequired == true
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.goalsLoading) Text("Loading goals…")
                    state.goalsError?.let {
                        FinanceMessage(it)
                        TextButton(onClick = { onEvent(GoalsEvent.ReloadGoals) }) { Text("Retry loading goals") }
                    }
                    if (state.retryGoal) {
                        OutlinedButton(onClick = onNewGoal) { Text("Review goal save") }
                    }
                    val pending = state.contribution
                    if (pending?.retryRequired == true && !state.showContribution) {
                        OutlinedButton(
                            onClick = { onEvent(GoalsEvent.OpenContribution(pending.goalId, pending.accountId)) },
                        ) { Text("Review pending contribution") }
                    }
                }
            }
        }
        if (state.goals.isEmpty() && !state.goalsLoading && state.goalsError == null) {
            item {
                Text("Start saving toward a goal", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onNewGoal) { Text("Create a goal") }
            }
        }
        items(state.goals, key = { "goal-" + it.id }) { goal ->
            val progress = finance.savedGoalProgress(goal)
            val target = goal.target.money.minor
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(goal.name, style = MaterialTheme.typography.titleLarge)
                    Text("Target ${lkrText(target)} · Due ${goal.deadline}", style = MaterialTheme.typography.bodyMedium)
                    if (progress == null) {
                        Text("Confirmed progress is unavailable.")
                    } else {
                        if (target > 0L) {
                            val fraction = (progress.confirmedReservedMinor.toDouble() / target)
                                .toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        SavedGoalPaceDetails(progress)
                    }
                    Button(
                        onClick = { onEvent(GoalsEvent.OpenContribution(goal.id, accounts.first().id)) },
                        enabled = editable && state.contribution == null && accounts.isNotEmpty() && progress != null,
                    ) { Text("Add contribution") }
                }
            }
        }
    }
}

@Composable
private fun NewGoalForm(state: GoalsUiState, onEvent: (GoalsEvent) -> Unit, onPreview: () -> Unit) {
    val editable = state.userId != null && !state.busy && !state.retryGoal
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GoalDraftField(
                    "Goal name", state.form.name, GoalField.NAME, state, onEvent,
                    "For example, a laptop or an emergency fund.",
                )
            }
            item {
                GoalDraftField(
                    "Target amount (LKR)", state.form.target, GoalField.TARGET, state, onEvent,
                    "Positive amount with up to two decimal places.",
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
                    "First monthly contribution", state.form.firstContributionDate,
                    GoalField.FIRST_CONTRIBUTION_DATE, state, onEvent,
                    "yyyy-MM-dd. Short months use their last day.",
                )
            }
            item {
                TextButton(onClick = { onEvent(GoalsEvent.ClearDraft) }, enabled = editable) {
                    Text("Clear draft")
                }
            }
        }

        FormActions {
            state.saveError?.let { FinanceMessage(it) }
            if (state.errors.isNotEmpty()) FinanceMessage("Review the highlighted fields.")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPreview, enabled = editable, modifier = Modifier.weight(1f)) {
                    Text("Preview plan", textAlign = TextAlign.Center)
                }
                Button(
                    onClick = { onEvent(GoalsEvent.SaveGoal) },
                    enabled = state.userId != null && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    val label = when {
                        state.busy -> "Saving…"
                        state.retryGoal -> "Retry save"
                        else -> "Save goal"
                    }
                    Text(label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun GoalPlanPreview(preview: GoalDraftPreview, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Goal plan preview") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(preview.name, style = MaterialTheme.typography.titleMedium)
                        Text(lkrText(preview.targetLkrMinor), style = MaterialTheme.typography.headlineSmall)
                        Text("${preview.startDate} to ${preview.deadline}")
                        Text("Assumes no money is reserved. This preview does not save the goal.")
                    }
                }
                item {
                    Text(
                        "${preview.pace.installments.size} monthly contributions",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(preview.pace.installments, key = { it.date.toString() }) { installment ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(installment.date.toString(), modifier = Modifier.weight(1f))
                        Text(lkrText(installment.amountLkrMinor), textAlign = TextAlign.End)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun SavedGoalPaceDetails(progress: SavedGoalProgress) {
    val pace = progress.pace
    Text("${lkrText(progress.confirmedReservedMinor)} reserved · ${lkrText(pace.remainingLkrMinor)} remaining")
    when (pace.status) {
        SavingsPaceStatus.SCHEDULED -> {
            val next = pace.installments.first()
            val last = pace.installments.last()
            val amount = if (next.amountLkrMinor == last.amountLkrMinor) {
                lkrText(next.amountLkrMinor)
            } else {
                "${lkrText(last.amountLkrMinor)}–${lkrText(next.amountLkrMinor)}"
            }
            Text("Required pace: $amount per month")
            Text("${pace.installments.size} monthly contributions left · Next ${next.date}")
        }
        SavingsPaceStatus.TARGET_REACHED -> Text("Savings target reached")
        SavingsPaceStatus.REPLAN_REQUIRED -> Text("No remaining contribution dates. Review this goal’s schedule.")
        SavingsPaceStatus.INACTIVE -> Text("This goal has no active savings schedule.")
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
