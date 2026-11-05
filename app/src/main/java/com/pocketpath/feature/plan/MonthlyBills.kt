package com.pocketpath.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.Category
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.MonthlyBillForm
import com.pocketpath.feature.finance.MonthlyBillsUiState
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage

@Composable
internal fun MonthlyBillsHeader(
    state: MonthlyBillsUiState,
    hasCategories: Boolean,
    onEvent: (FinanceEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Monthly bills", style = MaterialTheme.typography.titleLarge)
        when {
            state.error != null -> FinanceMessage(state.error)
            state.loading -> Text("Loading bills…")
            state.cached -> Text("Showing saved information. Connect to refresh bills.")
            state.occurrences.isEmpty() -> Text("No monthly bills yet.")
        }
        OutlinedButton(
            onClick = { onEvent(FinanceEvent.OpenMonthlyBill) },
            enabled = hasCategories || state.form != null,
        ) {
            Text(if (state.form?.retryRequired == true) "Review bill save" else "Add monthly bill")
        }
        if (!hasCategories && state.form == null) Text("Add or load a category to create a bill.")
    }
}

@Composable
internal fun MonthlyBillDialog(
    form: MonthlyBillForm,
    categories: List<Category>,
    onEvent: (FinanceEvent) -> Unit,
) {
    val editable = !form.busy && !form.retryRequired
    AlertDialog(
        onDismissRequest = { onEvent(FinanceEvent.CloseMonthlyBill) },
        title = { Text("Add monthly bill") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { onEvent(FinanceEvent.EditMonthlyBill(title = it)) },
                    label = { Text("Bill name") },
                    enabled = editable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FinanceChoice(
                    label = "Category",
                    selectedId = form.categoryId,
                    options = categories.map { it.id to it.name },
                    enabled = editable,
                    onSelected = { onEvent(FinanceEvent.EditMonthlyBill(categoryId = it)) },
                )
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEvent(FinanceEvent.EditMonthlyBill(amount = it)) },
                    label = { Text("Monthly amount (LKR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = editable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.dueDate,
                    onValueChange = { onEvent(FinanceEvent.EditMonthlyBill(dueDate = it)) },
                    label = { Text("First due date (YYYY-MM-DD)") },
                    enabled = editable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                form.error?.let { FinanceMessage(it) }
                if (form.retryRequired) {
                    Text("This bill may already be saved. Retry to check the same bill.")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(FinanceEvent.SaveMonthlyBill) }, enabled = !form.busy) {
                Text(when {
                    form.busy -> "Saving…"
                    form.retryRequired -> "Retry save"
                    else -> "Save bill"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(FinanceEvent.CloseMonthlyBill) }, enabled = !form.busy) {
                Text(if (form.retryRequired) "Close" else "Cancel")
            }
        },
    )
}
