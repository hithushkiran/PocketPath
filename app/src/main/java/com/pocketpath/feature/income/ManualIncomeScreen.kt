package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.ui.components.lkrText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualIncomeScreen(
    state: ManualIncomeUiState,
    onEvent: (ManualIncomeEvent) -> Unit,
) {
    val editable = state.referencesReady && !state.busy && !state.locked && !state.saved
    var sourceExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedDate = state.date.toLocalDateOrNull()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Record income", style = MaterialTheme.typography.headlineSmall)
        Text("Income is recorded online and increases the selected account balance.")

        if (state.loading) Text("Loading income sources and accounts…")
        if (!state.loading && state.selectableSources.isEmpty()) {
            Text("No active income sources are available.")
        }
        if (!state.loading && state.selectableAccounts.isEmpty()) {
            Text("No active LKR receiving accounts are available.")
        }

        IncomeChoice(
            label = "Income source",
            selected = state.selectableSources.firstOrNull { it.id == state.sourceId }?.name,
            expanded = sourceExpanded,
            enabled = editable,
            options = state.selectableSources.map { it.id to it.name },
            onExpandedChange = { sourceExpanded = it },
            onSelected = { onEvent(ManualIncomeEvent.SourceChanged(it)) },
        )
        IncomeChoice(
            label = "Receiving account",
            selected = state.selectableAccounts.firstOrNull { it.id == state.accountId }?.name,
            expanded = accountExpanded,
            enabled = editable,
            options = state.selectableAccounts.map { it.id to it.name },
            onExpandedChange = { accountExpanded = it },
            onSelected = { onEvent(ManualIncomeEvent.AccountChanged(it)) },
        )

        if (state.canRecordConvertedUsd || state.convertedUsd) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = state.convertedUsd,
                    enabled = editable,
                    role = Role.Checkbox,
                    onValueChange = { onEvent(ManualIncomeEvent.ConvertedUsdChanged(it)) },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = state.convertedUsd, onCheckedChange = null, enabled = editable)
                Text("AdSense payment converted from USD")
            }
        }

        OutlinedTextField(
            value = state.amount,
            onValueChange = { onEvent(ManualIncomeEvent.AmountChanged(it)) },
            label = { Text(if (state.convertedUsd) "Original amount (USD)" else "Amount (LKR)") },
            supportingText = { Text("Use at most two decimal places, without commas.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.convertedUsd) {
            OutlinedTextField(
                value = state.usdRate,
                onValueChange = { onEvent(ManualIncomeEvent.UsdRateChanged(it)) },
                label = { Text("Applied exchange rate (LKR per USD)") },
                supportingText = { Text("Enter the actual rate for this receipt, up to six decimal places.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )
            val credit = state.convertedCreditMinor
            if (credit != null) {
                Text("Account credit: ${lkrText(credit)}. Check this against the amount received.")
            } else if (state.amount.isNotBlank() && state.usdRate.isNotBlank()) {
                Text("Enter a positive USD amount and rate that produce a valid LKR credit.",
                    color = MaterialTheme.colorScheme.error)
            }
        }

        OutlinedButton(
            onClick = { showDatePicker = true },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.date.isBlank()) "Select receipt date" else "Receipt date: ${state.date}")
        }

        OutlinedTextField(
            value = state.note,
            onValueChange = { onEvent(ManualIncomeEvent.NoteChanged(it)) },
            label = { Text("Note (optional)") },
            supportingText = { Text("${state.note.length}/1000 characters") },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.saved) {
            Text(
                if (state.replayed) "Income was already recorded." else "Income recorded.",
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = { onEvent(ManualIncomeEvent.RecordAnother) }, modifier = Modifier.fillMaxWidth()) {
                Text("Record another income")
            }
        } else {
            if (state.locked && !state.busy) Text("Save is not confirmed. Retry without changing the entry.")
            Button(
                onClick = { onEvent(ManualIncomeEvent.Save) },
                enabled = state.referencesReady && !state.busy &&
                    state.selectableAccounts.isNotEmpty() && state.selectableSources.isNotEmpty() &&
                    (state.locked || !state.convertedUsd || state.convertedCreditMinor != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Saving…" else if (state.locked) "Retry save" else "Save income")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onEvent(ManualIncomeEvent.DateChanged(date.toString()))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = datePickerState) }
    }
}

@Composable
private fun IncomeChoice(
    label: String,
    selected: String?,
    expanded: Boolean,
    enabled: Boolean,
    options: List<Pair<String, String>>,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    Column {
        OutlinedButton(onClick = { onExpandedChange(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected ?: "Select $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSelected(id)
                    onExpandedChange(false)
                })
            }
        }
    }
}

private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: Exception) {
    null
}
