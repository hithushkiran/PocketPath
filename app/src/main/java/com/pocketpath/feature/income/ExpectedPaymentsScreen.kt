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
fun ExpectedPaymentsScreen(
    state: ExpectedPaymentsUiState,
    onCreate: () -> Unit,
    onCancel: (ExpectedPayment) -> Unit,
    onReceiptAction: (ReceiptAction) -> Unit = {},
    onSelectReceivingAccount: (String?) -> Unit = {},
) {
    var cancelling by remember { mutableStateOf<ExpectedPayment?>(null) }
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
        state.receiptError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        state.receiptMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.ledgerError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }

        ReceiptRecoveryCard(state, onReceiptAction, onSelectReceivingAccount)

        if (state.isEmpty) {
            Text("No active expected payments.")
        }

        state.payments.forEach { payment ->
            ExpectedPaymentCard(
                payment = payment,
                cancelling = state.cancellingPaymentId == payment.id,
                recordingReceipt = state.recordingReceiptPaymentId == payment.id,
                canRecordReceipt = state.canRecordReceipts,
                onCancel = { cancelling = payment },
                onReceipt = { onReceiptAction(ReceiptAction.Begin(payment.id)) },
            )
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
}

@Composable
private fun ExpectedPaymentCard(
    payment: ExpectedPayment,
    cancelling: Boolean,
    recordingReceipt: Boolean,
    canRecordReceipt: Boolean,
    onCancel: () -> Unit,
    onReceipt: () -> Unit,
) {
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
            OutlinedButton(
                onClick = onReceipt,
                enabled = canRecordReceipt && !cancelling && !recordingReceipt &&
                    payment.state == com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE &&
                    payment.amountDue.money.minor > payment.receivedAmountMinor,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (recordingReceipt) "Saving receipt…" else "Record receipt") }
        }
    }
}

@Composable
private fun ReceiptRecoveryCard(
    state: ExpectedPaymentsUiState,
    onAction: (ReceiptAction) -> Unit,
    onSelectAccount: (String?) -> Unit,
) {
    val recovery = state.receiptRecovery
    if (recovery.restoring) {
        Text("Restoring receipt recovery...")
        return
    }
    if (recovery.error != null) {
        Text(recovery.error, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    val receipt = recovery.receipt
    if (receipt == null) {
        if (recovery.storageBlocked) {
            Text("Receipt storage could not be read. New receipts are blocked; no submission has been started.")
            TextButton(onClick = { onAction(ReceiptAction.Reload) }) { Text("Reload recovery storage") }
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Receipt: ${receipt.projectLabel}", style = MaterialTheme.typography.titleMedium)
            if (receipt.phase == com.pocketpath.data.local.ReceiptRecoveryPhase.DRAFT) {
                Text("Unsubmitted draft. Cancelling this draft cannot cancel a submitted operation.")
                Text("Receiving account: ${receipt.accountId ?: "Select an account"}")
                state.accounts.filter(::isActiveLkrAccount).forEach { account ->
                    OutlinedButton(onClick = { onSelectAccount(account.id) }, enabled = !recovery.busy && !recovery.storageBlocked) {
                        Text(if (account.id == receipt.accountId) "Receiving: ${account.name}" else account.name)
                    }
                }
                OutlinedTextField(
                    value = receipt.amountText,
                    onValueChange = { onAction(ReceiptAction.Amount(it)) },
                    enabled = !recovery.busy && !recovery.storageBlocked,
                    label = { Text("LKR receipt amount") },
                )
                TextButton(onClick = { onAction(ReceiptAction.Submit) }, enabled = !recovery.busy && !recovery.storageBlocked) {
                    Text("Save receipt")
                }
                TextButton(onClick = { onAction(ReceiptAction.CancelDraft) }, enabled = !recovery.busy && !recovery.storageBlocked) {
                    Text("Cancel unsubmitted draft")
                }
                if (recovery.storageBlocked) {
                    TextButton(onClick = { onAction(ReceiptAction.Reload) }) { Text("Reload recovery storage") }
                }
            } else {
                val command = requireNotNull(receipt.command)
                Text("Review retained receipt")
                Text("Operation: ${command.operationId}")
                Text("Payment: ${command.paymentId}; original revision: ${command.originalRevision}")
                Text("Income source: ${command.sourceId}")
                Text("Receiving account: ${command.accountId}")
                Text("Amount: LKR ${Money.lkr(command.amountMinor).major().toPlainString()}")
                Text("Financial time: ${command.occurredAt}; ${command.localDate}; ${command.zone}")
                Text("Client time: ${command.clientCreatedAt}")
                val resolution = receipt.resolution
                (resolution?.serverOperation ?: recovery.reviewedOperation)?.let {
                    Text("Server operation from review:")
                    Text(it)
                }
                (resolution?.serverPayment ?: recovery.reviewedPayment)?.let {
                    Text("Payment state from review:")
                    Text(it)
                }
                if ((receipt.phase == com.pocketpath.data.local.ReceiptRecoveryPhase.CONFIRMED ||
                        receipt.phase == com.pocketpath.data.local.ReceiptRecoveryPhase.RESOLVED) && !recovery.storageBlocked) {
                    Text(resolution?.reason ?: if (receipt.replayed) "Confirmed: receipt was already recorded." else "Receipt confirmed.")
                    resolution?.let { Text("Inspected at: ${it.inspectedAt}") }
                    Text("Acknowledge this durably saved outcome before separately starting another receipt.")
                    TextButton(onClick = { onAction(ReceiptAction.Acknowledge) }, enabled = !recovery.busy) {
                        Text(if (receipt.phase == com.pocketpath.data.local.ReceiptRecoveryPhase.CONFIRMED)
                            "Acknowledge confirmed receipt" else "Acknowledge resolved conflict (not a saved receipt)")
                    }
                } else {
                    Text("This operation may already be recorded. A conflict requires review of these details; " +
                        "it never starts a new receipt. Retry uses the same ID, revision and payload. " +
                        "Leaving this screen or signing out retains recovery for this account.")
                    TextButton(onClick = { onAction(ReceiptAction.Retry) }, enabled = !recovery.busy) {
                        Text(if (recovery.busy) "Saving..." else if (receipt.phase != com.pocketpath.data.local.ReceiptRecoveryPhase.PREPARED) "Retry saving outcome" else "Retry retained receipt")
                    }
                    TextButton(onClick = { onAction(ReceiptAction.Review) }, enabled = !recovery.busy) {
                        Text("Review server outcome (read only)")
                    }
                }
            }
        }
    }
}

internal fun expectedPaymentStatusText(payment: ExpectedPayment): String = when (payment.state) {
    com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE -> when {
        payment.receivedAmountMinor == payment.amountDue.money.minor -> "Paid"
        payment.receivedAmountMinor > 0L -> "Partially received"
        else -> "Active"
    }
    com.pocketpath.domain.model.ExpectedPaymentState.CANCELLED -> "Cancelled"
}

/** Uses Money's exact decimal representation; it performs no currency conversion or floating arithmetic. */
internal fun expectedPaymentAmountText(payment: ExpectedPayment): String =
    "${payment.amountDue.money.currency.code} ${payment.amountDue.money.major().toPlainString()}"
