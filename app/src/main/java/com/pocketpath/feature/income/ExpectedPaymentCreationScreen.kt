package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** Unrouted creation form; it writes expected-payment planning data only. */
@Composable
fun ExpectedPaymentCreationScreen(
    state: ExpectedPaymentCreationUiState,
    onEvent: (ExpectedPaymentCreationEvent) -> Unit,
) {
    var sourcesExpanded by remember { mutableStateOf(false) }
    val editable = !state.busy && !state.locked && !state.saved
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add expected payment", style = MaterialTheme.typography.headlineSmall)
        Text("Expected income is planning information and does not change account balances.")
        if (state.loading) Text("Loading income sources…")
        OutlinedButton(onClick = { sourcesExpanded = true }, enabled = editable && state.eligibleSources.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(state.eligibleSources.firstOrNull { it.id == state.sourceId }?.name ?: "Select income source")
        }
        DropdownMenu(expanded = sourcesExpanded, onDismissRequest = { sourcesExpanded = false }) {
            state.eligibleSources.forEach { source -> DropdownMenuItem(text = { Text(source.name) }, onClick = { onEvent(ExpectedPaymentCreationEvent.SourceChanged(source.id)); sourcesExpanded = false }) }
        }
        if (!state.loading && state.eligibleSources.isEmpty()) Text("No active LKR income sources are available.")
        OutlinedTextField(state.projectLabel, { onEvent(ExpectedPaymentCreationEvent.ProjectChanged(it)) }, label = { Text("Project label") }, enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.milestoneLabel, { onEvent(ExpectedPaymentCreationEvent.MilestoneChanged(it)) }, label = { Text("Milestone (optional)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.amount, { onEvent(ExpectedPaymentCreationEvent.AmountChanged(it)) }, label = { Text("Amount (LKR)") }, supportingText = { Text("Use at most two decimal places, without commas.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.dueDate, { onEvent(ExpectedPaymentCreationEvent.DueDateChanged(it)) }, label = { Text("Due date") }, supportingText = { Text("yyyy-MM-dd") }, enabled = editable, modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.saved) {
            Text(if (state.replayed) "Expected payment was already saved." else "Expected payment saved.", color = MaterialTheme.colorScheme.primary)
            Button(onClick = { onEvent(ExpectedPaymentCreationEvent.CreateAnother) }, modifier = Modifier.fillMaxWidth()) { Text("Create another") }
        } else {
            if (state.locked && !state.busy) Text("Save is not confirmed. Retry without changing the payment.")
            Button(onClick = { onEvent(ExpectedPaymentCreationEvent.Save) }, enabled = state.eligibleSources.isNotEmpty() && !state.busy && !state.loading, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "Saving…" else if (state.locked) "Retry save" else "Save expected payment") }
        }
    }
}
