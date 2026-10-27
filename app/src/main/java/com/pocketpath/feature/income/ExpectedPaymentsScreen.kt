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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.ExpectedPayment

/** Reusable, read-only content. Navigation and write actions are intentionally absent. */
@Composable
fun ExpectedPaymentsScreen(state: ExpectedPaymentsUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Expected payments", style = MaterialTheme.typography.headlineSmall)
        Text("Expected amounts are planning information and do not change account balances.")

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

        if (state.isEmpty) {
            Text("No active expected payments.")
        }

        state.payments.forEach { payment ->
            ExpectedPaymentCard(payment)
        }
    }
}

@Composable
private fun ExpectedPaymentCard(payment: ExpectedPayment) {
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
        }
    }
}

internal fun expectedPaymentStatusText(payment: ExpectedPayment): String = when (payment.state) {
    com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE -> "Active"
    com.pocketpath.domain.model.ExpectedPaymentState.CANCELLED -> "Cancelled"
}

/** Uses Money's exact decimal representation; it performs no currency conversion or floating arithmetic. */
internal fun expectedPaymentAmountText(payment: ExpectedPayment): String =
    "${payment.amountDue.money.currency.code} ${payment.amountDue.money.major().toPlainString()}"
