package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.money.FiatCurrency

/** Expected-payment planning surface. Cancellation never posts a ledger event or account movement. */
@Composable
fun ExpectedPaymentsScreen(state: ExpectedPaymentsUiState, onCreate: () -> Unit, onCancel: (ExpectedPayment) -> Unit, onRecordReceipt: (ExpectedPayment, String?, String) -> Unit = { _, _, _ -> }) {
    var cancelling by remember { mutableStateOf<ExpectedPayment?>(null) }
    var receiving by remember { mutableStateOf<ExpectedPayment?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Expected payments", style = MaterialTheme.typography.headlineSmall)
        Text("Expected amounts are planning information and do not change account balances.")
        OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Add expected payment") }

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Loading expected payments…")
        }

        state.error?.let { failure ->
            Text(
                failure.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.cancellationError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        state.cancellationMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        if (state.isEmpty) {
            Text("No active expected payments.")
        }

        state.payments.forEach { payment ->
            ExpectedPaymentCard(payment, state.cancellingPaymentId == payment.id, { cancelling = payment }) { receiving = payment }
        }
    }
    cancelling?.let { payment ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel expected payment?") },
            text = { Text("This removes the payment from planning. It does not change account balances.") },
            confirmButton = {
                TextButton(onClick = { cancelling = null; onCancel(payment) }) { Text("Cancel payment") }
            },
            dismissButton = {
                TextButton(onClick = { cancelling = null }) { Text("Keep payment") }
            },
        )
    }
    receiving?.let { payment -> ReceiptDialog(payment, state.accounts, state.cancellingPaymentId == payment.id, onDismiss = { receiving = null }) { account, amount -> receiving = null; onRecordReceipt(payment, account, amount) } }
}

@Composable
private fun ExpectedPaymentCard(payment: ExpectedPayment, cancelling: Boolean, onCancel: () -> Unit, onReceipt: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(payment.projectLabel, style = MaterialTheme.typography.titleMedium)
            payment.milestoneLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(expectedPaymentStatusText(payment)) })
                AssistChip(onClick = {}, label = { Text("Due ${payment.dueDate}") })
            }
            Text(
                expectedPaymentAmountText(payment),
                style = MaterialTheme.typography.titleLarge,
            )
            Text("Received ${payment.amountDue.money.currency.code} ${Money(payment.amountDue.money.currency, payment.receivedAmountMinor).major().toPlainString()}")
            Text("Remaining ${payment.amountDue.money.currency.code} ${Money(payment.amountDue.money.currency, payment.amountDue.money.minor - payment.receivedAmountMinor).major().toPlainString()}")
            OutlinedButton(
                onClick = onCancel,
                enabled = !cancelling,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (cancelling) "Cancelling…" else "Cancel expected payment") }
            OutlinedButton(onClick = onReceipt, enabled = !cancelling && payment.amountDue.money.minor > payment.receivedAmountMinor, modifier = Modifier.fillMaxWidth()) { Text("Record receipt") }
        }
    }
}

@Composable private fun ReceiptDialog(payment: ExpectedPayment, accounts: List<Account>, busy: Boolean, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var account by remember { mutableStateOf(accounts.firstOrNull { it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null }?.id) }
    var amount by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Record receipt") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remaining ${expectedPaymentAmountText(payment.copy(receivedAmountMinor = payment.amountDue.money.minor - payment.receivedAmountMinor))}")
            accounts.filter { it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null }.forEach { item ->
                OutlinedButton(onClick = { account = item.id }, enabled = !busy) { Text(if (account == item.id) "Receiving: ${item.name}" else item.name) }
            }
            OutlinedTextField(value = amount, onValueChange = { amount = it }, enabled = !busy, label = { Text("LKR receipt amount") })
        }
    }, confirmButton = { TextButton(onClick = { onSave(account, amount) }, enabled = !busy) { Text(if (busy) "Saving…" else "Save receipt") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

internal fun expectedPaymentStatusText(payment: ExpectedPayment): String = when (payment.state) {
    com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE -> "Active"
    com.pocketpath.domain.model.ExpectedPaymentState.CANCELLED -> "Cancelled"
}

/** Uses Money's exact decimal representation; it performs no currency conversion or floating arithmetic. */
internal fun expectedPaymentAmountText(payment: ExpectedPayment): String =
    "${payment.amountDue.money.currency.code} ${payment.amountDue.money.major().toPlainString()}"
