package com.pocketpath.feature.transaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.feature.income.ManualIncomeEvent
import com.pocketpath.feature.income.ManualIncomeScreen
import com.pocketpath.feature.income.ManualIncomeUiState
import com.pocketpath.ui.components.SectionTabs

@Composable
internal fun TransactionEntryScreen(
    startWithIncome: Boolean,
    financeState: FinanceUiState,
    onFinanceEvent: (FinanceEvent) -> Unit,
    incomeState: ManualIncomeUiState,
    onIncomeEvent: (ManualIncomeEvent) -> Unit,
    onAccounts: () -> Unit,
    onActivity: () -> Unit,
) {
    var showIncome by rememberSaveable(startWithIncome) { mutableStateOf(startWithIncome) }
    Column(Modifier.fillMaxSize()) {
        SectionTabs(
            labels = listOf("Expense", "Income"),
            selectedIndex = if (showIncome) 1 else 0,
            onSelect = { showIncome = it == 1 },
        )

        if (showIncome) {
            ManualIncomeScreen(state = incomeState, onEvent = onIncomeEvent, onAccounts = onAccounts)
        } else {
            AddScreen(
                state = financeState,
                onEvent = onFinanceEvent,
                onAccounts = onAccounts,
                onActivity = onActivity,
            )
        }
    }
}
