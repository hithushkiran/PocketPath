package com.pocketpath.feature.finance

import com.pocketpath.domain.calculation.AccountBalance
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.Category
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.repository.WriteState
import com.pocketpath.domain.repository.ActivityCursor

data class CashAccountForm(
    val name: String = "",
    val opening: String = "0.00",
    val date: String = "",
    val busy: Boolean = false,
    val locked: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

data class CategoryForm(
    val name: String = "",
    val classification: CommitmentClass = CommitmentClass.DISCRETIONARY,
    val iconKey: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class ExpenseForm(
    val accountId: String? = null,
    val categoryId: String? = null,
    val amount: String = "",
    val date: String = "",
    val note: String = "",
    val classification: CommitmentClass = CommitmentClass.DISCRETIONARY,
    val busy: Boolean = false,
    val locked: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val writeState: WriteState? = null,
)

data class RejectedExpense(
    val operationId: String,
    val message: String,
)

data class FinanceUiState(
    val userId: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val balances: Map<String, AccountBalance> = emptyMap(),
    val poolBalanceMinor: Long? = null,
    val accountForm: CashAccountForm = CashAccountForm(),
    val expenseForm: ExpenseForm = ExpenseForm(),
    val categoryForm: CategoryForm = CategoryForm(),
    val showCategoryDialog: Boolean = false,
    val activity: List<FinancialTransaction> = emptyList(),
    val activityLoading: Boolean = false,
    val activityError: String? = null,
    val nextActivityCursor: ActivityCursor? = null,
    val draftsLoaded: Boolean = false,
    val draftSaving: Boolean = false,
    val localError: String? = null,
    val referencesReady: Boolean = false,
    val cached: Boolean = false,
    val lastConfirmedAt: String? = null,
    val sdkPendingIds: Set<String> = emptySet(),
    val receiptPendingIds: Set<String> = emptySet(),
    val uncertainSaveIds: Set<String> = emptySet(),
    val rejectedExpenses: List<RejectedExpense> = emptyList(),
) {
    val pendingExpenseIds: Set<String>
        get() = sdkPendingIds + receiptPendingIds

    val canEditDraft: Boolean
        get() = userId != null && draftsLoaded

    val canRecordExpense: Boolean
        get() = canEditDraft &&
            referencesReady &&
            localError == null &&
            selectableAccounts.isNotEmpty() &&
            selectableCategories.isNotEmpty()

    val hasUnresolvedLocalWork: Boolean
        get() = pendingExpenseIds.isNotEmpty() ||
            uncertainSaveIds.isNotEmpty() ||
            rejectedExpenses.isNotEmpty() ||
            (
                !accountForm.saved &&
                    (
                        accountForm.name.isNotBlank() ||
                            accountForm.opening !in setOf("", "0", "0.00")
                    )
            ) ||
            (
                !expenseForm.saved &&
                    (
                        expenseForm.amount.isNotBlank() ||
                            expenseForm.note.isNotBlank()
                    )
            )

    val ready: Boolean
        get() = userId != null && !loading && error == null

    val selectableAccounts: List<Account>
        get() = accounts.filter {
            it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null
        }

    val selectableCategories: List<Category>
        get() = categories.filter { it.metadata.archivedAt == null }
}

sealed interface FinanceEvent {
    data object ClearExpenseDraft : FinanceEvent
    data class ReviewRejectedExpense(val operationId: String) : FinanceEvent
    data object RetryConnection : FinanceEvent

    data class AccountNameChanged(val value: String) : FinanceEvent
    data class OpeningAmountChanged(val value: String) : FinanceEvent
    data class AccountDateChanged(val value: String) : FinanceEvent
    data object CreateAccount : FinanceEvent
    data object NewAccount : FinanceEvent

    data class ExpenseAccountChanged(val id: String) : FinanceEvent
    data class ExpenseCategoryChanged(val id: String) : FinanceEvent
    data class ExpenseAmountChanged(val value: String) : FinanceEvent
    data class ExpenseDateChanged(val value: String) : FinanceEvent
    data class ExpenseNoteChanged(val value: String) : FinanceEvent
    data class ExpenseClassChanged(val value: CommitmentClass) : FinanceEvent
    data object SaveExpense : FinanceEvent
    data object NewExpense : FinanceEvent

    data class CategoryNameChanged(val value: String) : FinanceEvent
    data class CategoryClassChanged(val value: CommitmentClass) : FinanceEvent
    data class CategoryIconChanged(val value: String) : FinanceEvent
    data class ToggleCategoryDialog(val show: Boolean) : FinanceEvent
    data object SaveCategory : FinanceEvent

    data object ReloadActivity : FinanceEvent
    data object LoadMoreActivity : FinanceEvent
}
