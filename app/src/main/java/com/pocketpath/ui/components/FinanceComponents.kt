package com.pocketpath.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.AccountBalance
import com.pocketpath.domain.calculation.BalanceUnits
import com.pocketpath.domain.calculation.BalanceWarning
import com.pocketpath.domain.model.Account
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import java.math.BigDecimal

fun lkrText(minor: Long): String =
    "LKR ${BigDecimal.valueOf(minor, 2).toPlainString()}"

fun balanceText(balance: AccountBalance?): String =
    when (val units = balance?.units) {
        is BalanceUnits.Fiat ->
            "${units.asset.code} ${
                BigDecimal.valueOf(units.minor, units.asset.scale).toPlainString()
            }"

        is BalanceUnits.Crypto ->
            "${units.asset.code} ${
                BigDecimal(units.atomic, units.asset.scale).toPlainString()
            }"

        null -> "—"
    }

@Composable
fun FinanceMessage(text: String, error: Boolean = true) {
    Text(
        text = text,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
fun FinanceStatus(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
) {
    if (state.loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Loading your financial data…")
    }

    state.error?.let {
        FinanceMessage(it)
        OutlinedButton(onClick = { onEvent(FinanceEvent.RetryConnection) }) {
            Text("Retry connection")
        }
    }

    when {
        state.pendingExpenseIds.isNotEmpty() -> {
            Text("${state.pendingExpenseIds.size} expense(s) awaiting synchronization.")
        }

        state.uncertainSaveIds.isNotEmpty() -> {
            Text("A save needs confirmation. Reconnect to check its result.")
        }

        state.cached -> {
            Text("Using cached data. Other-device changes may be missing.")
            state.lastConfirmedAt?.let {
                Text("Last complete refresh: $it")
            }
        }

        state.ready -> {
            Text("Financial data refreshed from the server.")
        }
    }

    if (state.draftsLoaded) {
        Text(
            if (state.draftSaving) {
                "Saving draft on this device…"
            } else {
                "Draft retained on this device."
            },
        )
    }

    state.localError?.let { FinanceMessage(it) }

    if (state.rejectedExpenses.isNotEmpty()) {
        FinanceMessage("Some expenses could not synchronize. Open Add to review them.")
    }
}

@Composable
fun FinanceBody(
    state: FinanceUiState,
    onEvent: (FinanceEvent) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FinanceStatus(state, onEvent)
        content()
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
fun AccountBalanceCard(
    account: Account,
    balance: AccountBalance?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(account.name, style = MaterialTheme.typography.titleMedium)
            Text(balanceText(balance), style = MaterialTheme.typography.titleLarge)

            if (account.metadata.archivedAt != null) {
                Text("Archived account")
            }

            if (balance?.warnings?.contains(
                    BalanceWarning.NEGATIVE_RECORDED_BALANCE,
                ) == true
            ) {
                FinanceMessage("Recorded balance is negative.")
            }
        }
    }
}

@Composable
fun FinanceChoice(
    label: String,
    selectedId: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$label: ${selectedLabel ?: "Select"}")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelected(id)
                    },
                )
            }
        }
    }
}
