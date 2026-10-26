package com.pocketpath.feature.transaction

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.repository.WriteState
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.*

@Composable
fun AddScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    onAccounts: () -> Unit,
    onActivity: () -> Unit,
) {
    val form = state.expenseForm
    val editable = state.canEditDraft && !form.busy && !form.locked && !form.saved

    FinanceBody(state, onEvent) {
        Text("Record an expense", style = MaterialTheme.typography.headlineSmall)

        if (state.ready && state.selectableAccounts.isEmpty()) {
            Text("Create an LKR cash account before recording an expense.")
            Button(onClick = onAccounts, modifier = Modifier.fillMaxWidth()) {
                Text("Set up an account")
            }
        }

        state.rejectedExpenses.forEach { rejected ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    FinanceMessage(rejected.message)
                    TextButton(
                        onClick = {
                            onEvent(
                                FinanceEvent.ReviewRejectedExpense(
                                    rejected.operationId,
                                ),
                            )
                        },
                    ) {
                        Text("Review this expense")
                    }
                }
            }
        }

        FinanceChoice(
            label = "Account",
            selectedId = form.accountId,
            options = state.selectableAccounts.map { it.id to it.name },
            enabled = editable,
            onSelected = { onEvent(FinanceEvent.ExpenseAccountChanged(it)) },
        )

        FinanceChoice(
            label = "Category",
            selectedId = form.categoryId,
            options = state.selectableCategories.map { it.id to it.name },
            enabled = editable,
            onSelected = { onEvent(FinanceEvent.ExpenseCategoryChanged(it)) },
        )

        OutlinedButton(
            onClick = { onEvent(FinanceEvent.ToggleCategoryDialog(true)) },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add custom category")
        }

        if (state.ready && state.selectableCategories.isEmpty()) {
            Text("No active categories are available.")
        }

        OutlinedTextField(
            value = form.amount,
            onValueChange = { onEvent(FinanceEvent.ExpenseAmountChanged(it)) },
            label = { Text("Amount (LKR)") },
            supportingText = { Text("Use at most two decimal places, without commas.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.date,
            onValueChange = { onEvent(FinanceEvent.ExpenseDateChanged(it)) },
            label = { Text("Expense date") },
            supportingText = { Text("yyyy-MM-dd") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Spending type", style = MaterialTheme.typography.titleMedium)

        Column(modifier = Modifier.selectableGroup()) {
            CommitmentClass.entries.forEach { classification ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = form.classification == classification,
                            enabled = editable,
                            role = Role.RadioButton,
                            onClick = {
                                onEvent(FinanceEvent.ExpenseClassChanged(classification))
                            },
                        ),
                ) {
                    RadioButton(
                        selected = form.classification == classification,
                        onClick = null,
                        enabled = editable,
                    )
                    Text(
                        text = when (classification) {
                            CommitmentClass.COMMITTED -> "Committed"
                            CommitmentClass.DISCRETIONARY -> "Discretionary"
                            CommitmentClass.UNPLANNED_ESSENTIAL -> "Unplanned essential"
                        },
                        modifier = Modifier.align(
                            androidx.compose.ui.Alignment.CenterVertically,
                        ),
                    )
                }
            }
        }

        OutlinedTextField(
            value = form.note,
            onValueChange = { onEvent(FinanceEvent.ExpenseNoteChanged(it)) },
            label = { Text("Note (optional)") },
            supportingText = { Text("${form.note.length}/1000 characters") },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        form.error?.let { FinanceMessage(it) }

        if (!form.busy && !form.locked && !form.saved) {
            TextButton(
                onClick = { onEvent(FinanceEvent.ClearExpenseDraft) },
            ) {
                Text("Clear draft")
            }
        }

        if (form.saved) {
            FinanceMessage(
                text = if (form.writeState == WriteState.CLOUD_CONFIRMED) {
                    "Expense synchronized."
                } else {
                    "Expense saved on this device. Awaiting synchronization."
                },
                error = false,
            )

            Button(onClick = onActivity, modifier = Modifier.fillMaxWidth()) {
                Text("View Activity")
            }

            OutlinedButton(
                onClick = { onEvent(FinanceEvent.NewExpense) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record another expense")
            }
        } else {
            if (form.locked && !form.busy) {
                Text("Save is not confirmed. Retry without changing the entry.")
            }

            Button(
                onClick = { onEvent(FinanceEvent.SaveExpense) },
                enabled = state.canRecordExpense &&
                    !form.busy &&
                    state.selectableAccounts.isNotEmpty() &&
                    state.selectableCategories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        form.busy -> "Saving…"
                        form.locked -> "Retry save"
                        else -> "Save expense"
                    },
                )
            }
        }

        if (state.showCategoryDialog) {
            val catForm = state.categoryForm
            AlertDialog(
                onDismissRequest = {
                    if (!catForm.busy) {
                        onEvent(FinanceEvent.ToggleCategoryDialog(false))
                    }
                },
                title = { Text("Add Custom Category") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = catForm.name,
                            onValueChange = { onEvent(FinanceEvent.CategoryNameChanged(it)) },
                            label = { Text("Category name") },
                            supportingText = { Text("${catForm.name.length}/80 characters") },
                            singleLine = true,
                            enabled = !catForm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text("Default commitment type", style = MaterialTheme.typography.titleSmall)

                        Column(modifier = Modifier.selectableGroup()) {
                            CommitmentClass.entries.forEach { classification ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = catForm.classification == classification,
                                            enabled = !catForm.busy,
                                            role = Role.RadioButton,
                                            onClick = {
                                                onEvent(FinanceEvent.CategoryClassChanged(classification))
                                            },
                                        ),
                                ) {
                                    RadioButton(
                                        selected = catForm.classification == classification,
                                        onClick = null,
                                        enabled = !catForm.busy,
                                    )
                                    Text(
                                        text = when (classification) {
                                            CommitmentClass.COMMITTED -> "Committed"
                                            CommitmentClass.DISCRETIONARY -> "Discretionary"
                                            CommitmentClass.UNPLANNED_ESSENTIAL -> "Unplanned essential"
                                        },
                                        modifier = Modifier.align(
                                            androidx.compose.ui.Alignment.CenterVertically,
                                        ),
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = catForm.iconKey,
                            onValueChange = { onEvent(FinanceEvent.CategoryIconChanged(it)) },
                            label = { Text("Icon key (optional)") },
                            singleLine = true,
                            enabled = !catForm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        catForm.error?.let { FinanceMessage(it) }

                        if (catForm.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(
                                    androidx.compose.ui.Alignment.CenterHorizontally,
                                ),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onEvent(FinanceEvent.SaveCategory) },
                        enabled = !catForm.busy && catForm.name.isNotBlank(),
                    ) {
                        Text(if (catForm.busy) "Saving…" else "Save Category")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onEvent(FinanceEvent.ToggleCategoryDialog(false)) },
                        enabled = !catForm.busy,
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
