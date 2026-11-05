package com.pocketpath.feature.finance

import com.pocketpath.domain.calculation.AccountBalance
import com.pocketpath.domain.calculation.BalanceUnits
import com.pocketpath.domain.calculation.FundsPlanningResult
import com.pocketpath.domain.calculation.HomeLedgerSummary
import com.pocketpath.domain.calculation.PurchaseImpactPreview
import com.pocketpath.domain.calculation.SavedGoalProgress
import com.pocketpath.domain.calculation.calculateSavedGoalProgress
import com.pocketpath.domain.calculation.previewPurchaseImpact
import com.pocketpath.domain.calculation.recordedHomeFunds
import com.pocketpath.domain.calculation.BudgetProgress
import com.pocketpath.domain.calculation.CommitmentBreakdown
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.Budget
import com.pocketpath.domain.model.BudgetPeriod
import com.pocketpath.domain.model.BudgetScope
import com.pocketpath.domain.model.Category
import com.pocketpath.domain.model.CryptoPosition
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.money.CryptoAsset
import com.pocketpath.domain.calculation.manualCryptoAssets
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.Goal
import com.pocketpath.domain.model.GoalState
import com.pocketpath.domain.model.MonthlyBill
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import java.time.YearMonth
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

data class VoidExpenseForm(
    val transactionId: String? = null,
    val reason: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class BudgetForm(
    val scope: BudgetScope = BudgetScope.OVERALL_DISCRETIONARY,
    val periodType: BudgetPeriod = BudgetPeriod.MONTHLY,
    val categoryId: String? = null,
    val limitAmount: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class MonthlyBillForm(
    val estimate: Boolean = false,
    val title: String = "",
    val categoryId: String? = null,
    val amount: String = "",
    val dueDate: String = "",
    val busy: Boolean = false,
    val retryRequired: Boolean = false,
    val error: String? = null,
)

data class BillPaymentForm(
    val occurrenceId: String,
    val title: String,
    val accountId: String,
    val amount: String,
    val date: String,
    val estimate: Boolean = false,
    val actualAmount: String = "",
    val busy: Boolean = false,
    val retryRequired: Boolean = false,
    val error: String? = null,
)

data class MonthlyBillsUiState(
    val occurrences: List<MonthlyBill> = emptyList(),
    val recurrenceError: String? = null,
    val coverageMonth: YearMonth? = null,
    val payment: BillPaymentForm? = null,
    val showPayment: Boolean = false,
    val loading: Boolean = true,
    val cached: Boolean = false,
    val error: String? = null,
    val form: MonthlyBillForm? = null,
    val showForm: Boolean = false,
) {
    val hasUnresolvedSave: Boolean
        get() = form?.let { it.busy || it.retryRequired } == true ||
            payment?.let { it.busy || it.retryRequired } == true
}

data class PurchasePreviewForm(
    val accountId: String? = null,
    val categoryId: String? = null,
    val amount: String = "",
) {
    val enteredAmount: EntryAmount?
        get() = runCatching { EntryAmount(Money.parse(amount.trim(), FiatCurrency.LKR)) }.getOrNull()
}

data class HomePurchaseResult(
    val impact: PurchaseImpactPreview,
    val accountInEverydayPool: Boolean,
    val unreservedAccountBalanceAfterMinor: Long,
    val reservedInAccountMinor: Long,
)

data class CryptoUiState(
    val positions: Map<String, CryptoPosition> = emptyMap(),
    val loading: Boolean = true,
    val cached: Boolean = false,
    val positionsError: String? = null,
    val assetCode: String? = null,
    val trade: CryptoTradeForm = CryptoTradeForm(),
    val busy: Boolean = false,
    val retryWallet: Boolean = false,
    val retryTrade: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val hasUnresolvedWrite: Boolean
        get() = busy || retryWallet || retryTrade
}

data class CryptoTradeForm(
    val kind: TransactionKind = TransactionKind.CRYPTO_BUY,
    val walletId: String? = null,
    val quoteAccountId: String? = null,
    val quantity: String = "",
    val gross: String = "",
    val fee: String = "",
    val date: String = "",
)

data class FinanceUiState(
    val userId: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val overallMonthlyBudgetProgress: BudgetProgress? = null,
    val overallWeeklyBudgetProgress: BudgetProgress? = null,
    val categoryBudgetProgresses: List<Pair<String, BudgetProgress>> = emptyList(),
    val monthlyCommitmentBreakdown: CommitmentBreakdown? = null,
    val balances: Map<String, AccountBalance> = emptyMap(),
    val poolBalanceMinor: Long? = null,
    val goalReservationsMinor: Map<String, Long>? = null,
    val crypto: CryptoUiState = CryptoUiState(),
    val homeLedger: HomeLedgerSummary? = null,
    val purchaseForm: PurchasePreviewForm? = null,
    val accountForm: CashAccountForm = CashAccountForm(),
    val expenseForm: ExpenseForm = ExpenseForm(),
    val categoryForm: CategoryForm = CategoryForm(),
    val showCategoryDialog: Boolean = false,
    val voidForm: VoidExpenseForm = VoidExpenseForm(),
    val showVoidDialog: Boolean = false,
    val budgetForm: BudgetForm = BudgetForm(),
    val showBudgetDialog: Boolean = false,
    val monthlyBills: MonthlyBillsUiState = MonthlyBillsUiState(),
    val activity: List<FinancialTransaction> = emptyList(),
    val activityLoading: Boolean = false,
    val activityError: String? = null,
    val budgetError: String? = null,
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

    fun savedGoalProgress(goal: Goal): SavedGoalProgress? {
        if (!ready) return null
        val ledger = homeLedger ?: return null
        val reservations = goalReservationsMinor ?: return null
        val reserved = reservations[goal.id] ?: 0L
        if (reserved < 0L) return null
        return calculateSavedGoalProgress(goal, reserved, ledger.date)
    }

    fun featuredGoalProgress(goals: List<Goal>): SavedGoalProgress? {
        val reservations = goalReservationsMinor ?: return null
        val goal = goals.asSequence()
            .filter { it.state == GoalState.ACTIVE }
            .minWithOrNull(compareBy<Goal> { (reservations[it.id] ?: 0L) >= it.target.money.minor }
                .thenBy(Goal::deadline).thenBy(Goal::id))
            ?: return null
        return savedGoalProgress(goal)
    }

    val homeFunds: FundsPlanningResult.Ready?
        get() {
            val ledger = homeLedger ?: return null
            val balance = poolBalanceMinor ?: return null
            if (!ready || monthlyBills.loading || monthlyBills.error != null ||
                monthlyBills.coverageMonth != YearMonth.from(ledger.date)
            ) return null
            val everydayIds = accounts.filter { it.includeInEverydayPool }.map(Account::id).toSet()
            return runCatching { recordedHomeFunds(ledger, balance, everydayIds, monthlyBills.occurrences) }.getOrNull()
        }

    fun purchaseResult(plan: FundsPlanningResult.Ready?): HomePurchaseResult? {
        val form = purchaseForm ?: return null
        val input = plan?.input ?: return null
        val amount = form.enteredAmount ?: return null
        val account = selectableAccounts.firstOrNull { it.id == form.accountId } ?: return null
        if (selectableCategories.none { it.id == form.categoryId }) return null
        val balance = balances[account.id]?.units as? BalanceUnits.Fiat ?: return null
        val reserved = homeLedger?.reservedByAccount?.get(account.id) ?: 0L
        return runCatching {
            HomePurchaseResult(
                impact = previewPurchaseImpact(input, amount, payingAccountInPool = account.includeInEverydayPool),
                accountInEverydayPool = account.includeInEverydayPool,
                unreservedAccountBalanceAfterMinor = Math.subtractExact(
                    Math.subtractExact(balance.minor, reserved), amount.money.minor,
                ),
                reservedInAccountMinor = reserved,
            )
        }.getOrNull()
    }

    val selectableAccounts: List<Account>
        get() = accounts.filter {
            it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null
        }

    val cryptoWallets: List<Account>
        get() = accounts.filter { it.asset is CryptoAsset && it.metadata.archivedAt == null }

    val availableCryptoAssets: List<CryptoAsset>
        get() = manualCryptoAssets.filter { asset -> cryptoWallets.none { it.asset == asset } }

    val selectedCryptoWallet: Account?
        get() = cryptoWallets.firstOrNull { it.id == crypto.trade.walletId } ?: cryptoWallets.firstOrNull()

    val selectedCryptoQuoteAccount: Account?
        get() = selectableAccounts.firstOrNull { it.id == crypto.trade.quoteAccountId } ?: selectableAccounts.firstOrNull()

    val selectableCategories: List<Category>
        get() = categories.filter { it.metadata.archivedAt == null }
}

sealed interface FinanceEvent {
    data class SelectCryptoAsset(val code: String) : FinanceEvent
    data object CreateCryptoWallet : FinanceEvent
    data class EditCryptoTrade(
        val kind: TransactionKind? = null,
        val walletId: String? = null,
        val quoteAccountId: String? = null,
        val quantity: String? = null,
        val gross: String? = null,
        val fee: String? = null,
        val date: String? = null,
    ) : FinanceEvent
    data object SaveCryptoTrade : FinanceEvent
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

    data class OpenVoidDialog(val transactionId: String) : FinanceEvent
    data class VoidReasonChanged(val value: String) : FinanceEvent
    data class ToggleVoidDialog(val show: Boolean) : FinanceEvent
    data object SubmitVoidExpense : FinanceEvent

    data class BudgetScopeChanged(val scope: BudgetScope) : FinanceEvent
    data class BudgetPeriodChanged(val period: BudgetPeriod) : FinanceEvent
    data class BudgetCategoryChanged(val categoryId: String) : FinanceEvent
    data class BudgetLimitChanged(val amount: String) : FinanceEvent
    data class ToggleBudgetDialog(val show: Boolean) : FinanceEvent
    data object SubmitBudget : FinanceEvent

    data object OpenMonthlyBill : FinanceEvent
    data object CloseMonthlyBill : FinanceEvent
    data class EditMonthlyBill(
        val estimate: Boolean? = null,
        val title: String? = null,
        val categoryId: String? = null,
        val amount: String? = null,
        val dueDate: String? = null,
    ) : FinanceEvent
    data object SaveMonthlyBill : FinanceEvent
    data object OpenPurchasePreview : FinanceEvent
    data object ClosePurchasePreview : FinanceEvent
    data class EditPurchasePreview(
        val accountId: String? = null,
        val categoryId: String? = null,
        val amount: String? = null,
    ) : FinanceEvent
    data class OpenBillPayment(val occurrenceId: String) : FinanceEvent
    data object CloseBillPayment : FinanceEvent
    data class EditBillPayment(
        val actualAmount: String? = null,
        val accountId: String? = null,
        val amount: String? = null,
        val date: String? = null,
    ) : FinanceEvent
    data object SaveBillActualAmount : FinanceEvent
    data object SubmitBillPayment : FinanceEvent

    data object ReloadActivity : FinanceEvent
    data object LoadMoreActivity : FinanceEvent
}
