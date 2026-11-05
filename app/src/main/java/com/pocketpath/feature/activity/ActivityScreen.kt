package com.pocketpath.feature.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.FinanceStatus
import com.pocketpath.ui.components.groupedAmount
import com.pocketpath.ui.components.lkrText
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ActivityScreen(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
) {
    val accountNames = state.accounts.associate { it.id to it.name }
    val categoryNames = state.categories.associate { it.id to it.name }
    val pendingIds = state.pendingExpenseIds
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Transactions",
                    style = MaterialTheme.typography.titleLarge,
                )
                FinanceStatus(state, onEvent, showRoutineMessages = false)
                if (state.cached && pendingIds.isNotEmpty()) {
                    Text("Showing cached activity.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.activity.isEmpty() &&
            state.ready &&
            !state.activityLoading &&
            state.activityError == null
        ) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No transactions yet", style = MaterialTheme.typography.titleMedium)
                        Text("Tap Add to record income or an expense.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        itemsIndexed(state.activity, key = { _, event -> event.id }) { index, event ->
            val date = event.financialTime.occurredLocalDate
            val previousDate = state.activity.getOrNull(index - 1)?.financialTime?.occurredLocalDate
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (date != previousDate) {
                    Text(
                        date.format(activityDateFormat),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TransactionCard(
                    event = event,
                    accountNames = accountNames,
                    category = event.components.firstOrNull()?.categoryId?.let { categoryNames[it] },
                    pending = event.id in pendingIds,
                    onVoid = { onEvent(FinanceEvent.OpenVoidDialog(event.id)) },
                )
            }
        }

        state.activityError?.let { message ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinanceMessage(message)
                    OutlinedButton(
                        onClick = { onEvent(FinanceEvent.ReloadActivity) },
                    ) {
                        Text("Reload Activity")
                    }
                }
            }
        }

        if (state.activityLoading) {
            item { CircularProgressIndicator() }
        }

        if (state.nextActivityCursor != null) {
            item {
                OutlinedButton(
                    onClick = { onEvent(FinanceEvent.LoadMoreActivity) },
                    enabled = state.ready && !state.activityLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load more")
                }
            }
        }
    }

    if (state.showVoidDialog) {
        val voidForm = state.voidForm
        val targetTx = state.activity.firstOrNull { it.id == voidForm.transactionId }

        AlertDialog(
            onDismissRequest = {
                if (!voidForm.busy) {
                    onEvent(FinanceEvent.ToggleVoidDialog(false))
                }
            },
            title = { Text("Void Expense") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    targetTx?.movements?.singleOrNull()?.amount?.let { amount ->
                        Text(
                            "Amount: ${amount.activityAmount()}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Text(
                        "Voiding this expense will restore your account balance and " +
                            "exclude it from budgets and totals, while preserving its record in history.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    OutlinedTextField(
                        value = voidForm.reason,
                        onValueChange = { onEvent(FinanceEvent.VoidReasonChanged(it)) },
                        label = { Text("Reason for voiding") },
                        supportingText = { Text("${voidForm.reason.length}/1000 characters") },
                        singleLine = true,
                        enabled = !voidForm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    voidForm.error?.let { FinanceMessage(it) }

                    if (voidForm.busy) {
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
                    onClick = { onEvent(FinanceEvent.SubmitVoidExpense) },
                    enabled = !voidForm.busy && voidForm.reason.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (voidForm.busy) "Voiding…" else "Void Expense")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(FinanceEvent.ToggleVoidDialog(false)) },
                    enabled = !voidForm.busy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private val activityDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
private fun TransactionCard(
    event: FinancialTransaction,
    accountNames: Map<String, String>,
    category: String?,
    pending: Boolean,
    onVoid: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val voided = event.status == TransactionStatus.VOIDED
    val canVoid = event.kind == TransactionKind.EXPENSE && !voided
    val movement = event.movements.singleOrNull()
    val reservation = event.reservationEffects.singleOrNull()
    val trade = event.cryptoTrade
    val accountId = movement?.accountId ?: reservation?.backingAccountId ?: trade?.assetAccountId
    val account = accountId?.let { accountNames[it] }
    val accountLabel = if (event.kind == TransactionKind.GOAL_RESERVE && reservation != null) {
        account?.let { "Reserved in $it" } ?: "Reserved funds"
    } else account
    val receipt = event.components.singleOrNull()?.takeIf {
        event.kind == TransactionKind.INCOME && it.originalAmount != null && it.valuationRate != null
    }
    val showMovements = movement == null && trade == null && event.movements.isNotEmpty()
    val hasDetails = canVoid || voided || trade != null || receipt != null || showMovements ||
        !event.note.isNullOrBlank() || event.kind == TransactionKind.OPENING
    var expanded by rememberSaveable { mutableStateOf(false) }
    val detailsLabel = if (expanded) "Hide details" else "Show details"
    val detailsModifier = if (hasDetails) {
        Modifier.clickable(onClickLabel = detailsLabel) { expanded = !expanded }
    } else Modifier
    val amount = when {
        trade != null -> NativeAmount.Crypto(trade.quantity).activityAmount()
        movement != null -> movement.amount.activityAmount(showPlus = event.kind == TransactionKind.INCOME)
        reservation != null -> lkrText(reservation.amountLkr.minor)
        else -> null
    }
    val amountColor = when {
        voided -> colors.onSurfaceVariant
        event.kind == TransactionKind.INCOME -> colors.secondary
        event.kind == TransactionKind.EXPENSE -> colors.error
        else -> colors.onSurface
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().then(detailsModifier).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(transactionTitle(event.kind, category), style = type.titleMedium)
                    accountLabel?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
                }
                if (amount != null) {
                    Text(
                        amount,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = type.titleMedium,
                        color = amountColor,
                    )
                }
            }
            if (voided) Text("Voided", style = type.labelMedium, color = colors.error)
            if (pending) Text("Awaiting synchronization", style = type.labelMedium, color = colors.tertiary)
            if (hasDetails) Text(detailsLabel, style = type.labelMedium, color = colors.primary)
        }

        if (expanded && hasDetails) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (trade != null) {
                    Text("Trade value: ${lkrText(trade.proceedsOrAcquisitionLkr.minor)}", style = type.bodyMedium)
                    for (fee in trade.fees) {
                        Text("Fee: ${lkrText(fee.lkrAmount.minor)}", style = type.bodyMedium)
                    }
                    val cash = event.movements.firstOrNull { it.accountId == trade.quoteAccountId }?.amount
                    (cash as? NativeAmount.Fiat)?.let {
                        Text("Cash movement: ${it.activityAmount()}", style = type.bodyMedium)
                    }
                    when (val result = trade.realizedPnl) {
                        is KnownLkrAmount.Known ->
                            Text("Realized gain/loss: ${lkrText(result.amount.minor)}", style = type.bodyMedium)
                        is KnownLkrAmount.Unknown ->
                            Text("Realized gain/loss unavailable", style = type.bodyMedium)
                        null -> Unit
                    }
                }
                val original = receipt?.originalAmount
                val rate = receipt?.valuationRate
                if (original != null && rate != null) {
                    Text("Original amount: ${original.activityAmount()}", style = type.bodyMedium)
                    Text(
                        "Applied receipt rate: ${rate.lkrPerMajor.stripTrailingZeros().toPlainString()} LKR per ${original.asset.code}",
                        style = type.bodyMedium,
                    )
                }
                if (showMovements) {
                    for (entry in event.movements) {
                        val name = accountNames[entry.accountId] ?: "Account"
                        Text("$name: ${entry.amount.activityAmount()}", style = type.bodyMedium)
                    }
                }
                event.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.bodyMedium) }
                event.voidMetadata?.voidReason?.let {
                    Text("Void reason: $it", style = type.bodyMedium, color = colors.error)
                }
                if (event.kind == TransactionKind.OPENING) {
                    Text("Included in your account balance and excluded from income.", style = type.bodyMedium)
                }
                if (canVoid) {
                    TextButton(
                        onClick = onVoid,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    ) {
                        Text("Void expense")
                    }
                }
            }
        }
    }
}

private fun transactionTitle(kind: TransactionKind, category: String?): String = when (kind) {
    TransactionKind.OPENING -> "Opening balance"
    TransactionKind.INCOME -> "Income"
    TransactionKind.EXPENSE -> category?.let { "Expense · $it" } ?: "Expense"
    TransactionKind.TRANSFER -> "Transfer"
    TransactionKind.FX_CONVERSION -> "Currency conversion"
    TransactionKind.CRYPTO_BUY -> "Crypto buy"
    TransactionKind.CRYPTO_SELL -> "Crypto sell"
    TransactionKind.REFUND -> "Refund"
    TransactionKind.REIMBURSEMENT -> "Reimbursement"
    TransactionKind.ADJUSTMENT -> "Adjustment"
    TransactionKind.GOAL_RESERVE -> "Goal contribution"
    TransactionKind.GOAL_RELEASE -> "Goal release"
    TransactionKind.GOAL_PURCHASE -> "Goal purchase"
}

private fun NativeAmount.activityAmount(showPlus: Boolean = false): String {
    val value = major()
    val sign = when {
        value.signum() < 0 -> "−"
        showPlus && value.signum() > 0 -> "+"
        else -> ""
    }
    val magnitude = when (this) {
        is NativeAmount.Fiat -> groupedAmount(value.abs())
        is NativeAmount.Crypto -> value.abs().stripTrailingZeros().toPlainString()
    }
    return "$sign${asset.code} $magnitude"
}
