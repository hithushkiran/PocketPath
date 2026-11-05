package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.IncomeSourceKind

@Composable
fun IncomeSourcesScreen(
    state: ManualIncomeUiState,
    onEvent: (ManualIncomeEvent) -> Unit,
) {
    val sources = state.selectableSources

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Income sources", style = MaterialTheme.typography.headlineSmall)
        Text("Use sources to describe where your confirmed LKR income comes from.")

        OutlinedButton(
            onClick = { onEvent(ManualIncomeEvent.OpenSourceManagement) },
            enabled = !state.sourceManagement.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add income source")
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when {
            state.loading && sources.isEmpty() -> Text("Loading income sources…")
            !state.loading && sources.isEmpty() -> Text("No income sources yet. Add Salary, Freelance, or Allowance to get started.")
            else -> sources.forEach { source ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text(kindLabel(source.kind)) })
                            AssistChip(onClick = {}, label = { Text("LKR") })
                        }
                    }
                }
            }
        }
    }

    if (state.sourceManagement.formOpen) {
        IncomeSourceDialog(state = state.sourceManagement, onEvent = onEvent)
    }
}

@Composable
private fun IncomeSourceDialog(
    state: IncomeSourceManagementUiState,
    onEvent: (ManualIncomeEvent) -> Unit,
) {
    val editable = !state.busy && !state.locked && !state.saved
    AlertDialog(
        onDismissRequest = { onEvent(ManualIncomeEvent.CloseSourceManagement) },
        title = { Text("Add income source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onEvent(ManualIncomeEvent.SourceManagementNameChanged(it)) },
                    label = { Text("Source name") },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Kind", style = MaterialTheme.typography.labelLarge)
                SourceKindChoices(kind = state.kind, enabled = editable, onEvent = onEvent)
                Text("Currency: LKR", style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saved) {
                    Text(
                        if (state.replayed) "Already saved." else "Income source created.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (state.locked && !state.busy) {
                    Text("Save is not confirmed. Retry without changing this source.")
                }
            }
        },
        confirmButton = {
            if (state.saved) {
                TextButton(onClick = { onEvent(ManualIncomeEvent.CreateAnotherSource) }) {
                    Text("Create another")
                }
            } else {
                TextButton(
                    onClick = { onEvent(ManualIncomeEvent.SaveSource) },
                    enabled = !state.busy,
                ) {
                    Text(
                        when {
                            state.busy -> "Saving…"
                            state.locked -> "Retry save"
                            else -> "Save"
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(ManualIncomeEvent.CloseSourceManagement) },
                enabled = !state.busy,
            ) {
                Text(if (state.saved) "Done" else "Cancel")
            }
        },
    )
}

@Composable
private fun SourceKindChoices(
    kind: IncomeSourceKind,
    enabled: Boolean,
    onEvent: (ManualIncomeEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            IncomeSourceKind.SALARY to "Salary",
            IncomeSourceKind.FREELANCE to "Freelance",
            IncomeSourceKind.OTHER to "Allowance / other",
        ).forEach { (value, label) ->
            FilterChip(
                selected = kind == value,
                onClick = { onEvent(ManualIncomeEvent.SourceManagementKindChanged(value)) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

private fun kindLabel(kind: IncomeSourceKind): String = when (kind) {
    IncomeSourceKind.SALARY -> "Salary"
    IncomeSourceKind.FREELANCE -> "Freelance"
    IncomeSourceKind.OTHER -> "Allowance / other"
    IncomeSourceKind.ADSENSE -> "AdSense"
    IncomeSourceKind.CRYPTO -> "Crypto"
}
