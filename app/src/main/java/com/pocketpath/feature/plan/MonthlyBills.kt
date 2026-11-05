package com.pocketpath.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.Category
import com.pocketpath.domain.model.MonthlyBill
import com.pocketpath.feature.finance.BillPaymentForm
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.MonthlyBillForm
import com.pocketpath.feature.finance.MonthlyBillsUiState
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.lkrText
import java.time.LocalDate

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
        state.recurrenceError?.let { FinanceMessage("Upcoming bills could not be refreshed. $it") }
        state.payment?.takeIf { it.retryRequired && !state.showPayment }?.let { pending ->
            OutlinedButton(onClick = { onEvent(FinanceEvent.OpenBillPayment(pending.occurrenceId)) }) {
                Text("Review payment")
            }
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
                FinanceChoice(
                    label = "Amount type",
                    selectedId = if (form.estimate) "estimate" else "fixed",
                    options = listOf("fixed" to "Fixed", "estimate" to "Monthly estimate"),
                    enabled = editable,
                    onSelected = { onEvent(FinanceEvent.EditMonthlyBill(estimate = it == "estimate")) },
                )
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEvent(FinanceEvent.EditMonthlyBill(amount = it)) },
                    label = { Text(if (form.estimate) "Monthly estimate (LKR)" else "Monthly amount (LKR)") },
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

@Composable
internal fun MonthlyBillCard(
    bill: MonthlyBill,
    today: LocalDate,
    canPay: Boolean,
    onPay: () -> Unit,
) {
    val due = bill.occurrence.effectiveDueDate
    val status = when {
        bill.remainingMinor == 0L -> "Paid"
        due < today && bill.paidMinor > 0L -> "Partially paid · Overdue"
        due < today -> "Overdue"
        bill.paidMinor > 0L -> "Partially paid"
        due == today -> "Due today"
        else -> "Upcoming"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(bill.occurrence.titleSnapshot, style = MaterialTheme.typography.titleMedium)
            Text("Due $due · $status")
            Text("Paid ${lkrText(bill.paidMinor)} · Remaining ${lkrText(bill.remainingMinor)}")
            if (bill.occurrence.amountMode == AmountMode.ESTIMATE) {
                Text("Estimate ${lkrText(bill.occurrence.expectedAmount.money.minor)} · Actual ${bill.actualAmount?.money?.minor?.let(::lkrText) ?: "not entered"}")
            }
            if (bill.remainingMinor > 0L || bill.occurrence.amountMode == AmountMode.ESTIMATE) {
                OutlinedButton(onClick = onPay, enabled = canPay) {
                    Text(if (bill.occurrence.amountMode == AmountMode.ESTIMATE) "Actual amount / payment" else "Record payment")
                }
            }
        }
    }
}

@Composable
internal fun BillPaymentDialog(
    form: BillPaymentForm,
    remainingMinor: Long?,
    accounts: List<Account>,
    onEvent: (FinanceEvent) -> Unit,
) {
    val editable = !form.busy && !form.retryRequired
    AlertDialog(
        onDismissRequest = { onEvent(FinanceEvent.CloseBillPayment) },
        title = { Text("Pay ${form.title}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                remainingMinor?.let { Text("Remaining ${lkrText(it)}") }
                if (form.estimate) {
                    OutlinedTextField(
                        value = form.actualAmount,
                        onValueChange = { onEvent(FinanceEvent.EditBillPayment(actualAmount = it)) },
                        label = { Text("This bill's actual amount (LKR)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { onEvent(FinanceEvent.SaveBillActualAmount) }, enabled = editable) {
                        Text("Save actual amount")
                    }
                }
                FinanceChoice(
                    label = "Paying account",
                    selectedId = form.accountId,
                    options = accounts.map { it.id to it.name },
                    enabled = editable,
                    onSelected = { onEvent(FinanceEvent.EditBillPayment(accountId = it)) },
                )
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEvent(FinanceEvent.EditBillPayment(amount = it)) },
                    label = { Text("Payment amount (LKR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.date,
                    onValueChange = { onEvent(FinanceEvent.EditBillPayment(date = it)) },
                    label = { Text("Payment date (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                form.error?.let { FinanceMessage(it) }
                if (form.retryRequired) Text("This payment may already be recorded. Retry checks the same payment.")
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(FinanceEvent.SubmitBillPayment) }, enabled = !form.busy) {
                Text(when {
                    form.busy -> "Saving…"
                    form.retryRequired -> "Retry payment"
                    else -> "Save payment"
                })
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(FinanceEvent.CloseBillPayment) },
                enabled = !form.busy,
            ) {
                Text(if (form.retryRequired) "Close" else "Cancel")
            }
        },
    )
}
