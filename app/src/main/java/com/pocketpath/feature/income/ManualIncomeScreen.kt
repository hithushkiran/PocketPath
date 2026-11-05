package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.FormActions
import com.pocketpath.ui.components.lkrText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualIncomeScreen(
    state: ManualIncomeUiState,
    onEvent: (ManualIncomeEvent) -> Unit,
    onAccounts: () -> Unit,
) {
    val editable = state.referencesReady && !state.busy && !state.locked && !state.saved
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedDate = state.date.toLocalDateOrNull()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) Text("Loading income sources and accounts…")
            if (!state.loading && state.selectableSources.isEmpty()) {
                Text("Add a source such as Salary, Freelance, or AdSense.")
            }
            if (!state.loading && state.selectableAccounts.isEmpty()) {
                Text("Add an LKR account to receive income.")
                TextButton(onClick = onAccounts) { Text("Manage accounts") }
            }

            FinanceChoice(
                label = "Income source",
                selectedId = state.sourceId,
                enabled = editable,
                options = state.selectableSources.map { it.id to it.name },
                onSelected = { onEvent(ManualIncomeEvent.SourceChanged(it)) },
            )
            TextButton(
                onClick = {
                    onEvent(
                        if (state.sourceManagement.saved) ManualIncomeEvent.CreateAnotherSource
                        else ManualIncomeEvent.OpenSourceManagement,
                    )
                },
                enabled = !state.busy && !state.locked && !state.saved && !state.sourceManagement.busy,
            ) {
                Text("Add income source")
            }
            FinanceChoice(
                label = "Receiving account",
                selectedId = state.accountId,
                enabled = editable,
                options = state.selectableAccounts.map { it.id to it.name },
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
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FormActions {
            state.error?.let { FinanceMessage(it) }

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
        }
    }

    if (state.sourceManagement.formOpen) {
        IncomeSourceDialog(state = state.sourceManagement, onEvent = onEvent)
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

private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: Exception) {
    null
}
