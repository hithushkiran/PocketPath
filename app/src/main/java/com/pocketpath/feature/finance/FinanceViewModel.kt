package com.pocketpath.feature.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.local.FinanceLocalStore
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.data.repository.FirebaseFoundationRepository
import com.pocketpath.domain.calculation.BalanceUnits
import com.pocketpath.domain.calculation.accountBalance
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class FinanceViewModel @Inject constructor(
    auth: AuthRepository,
    private val factory: FirebaseFoundationFactory,
    private val local: FinanceLocalStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FinanceUiState())
    val state = _state.asStateFlow()

    private val reload = MutableStateFlow(0)

    private var generation = 0
    private var repository: FirebaseFoundationRepository? = null
    private var sessionScope: CoroutineScope? = null
    private var zone = ZoneId.of("Asia/Colombo")

    private var drafts: FinanceDraftController? = null
    private var draftRevision = 0L

    private var pendingAccount: CreateAccountCommand?
        get() = drafts?.accountCommand
        set(value) {
            drafts?.accountCommand = value
        }

    private var pendingExpense: RecordExpenseCommand?
        get() = drafts?.expenseCommand
        set(value) {
            drafts?.expenseCommand = value
        }

    private var pendingBudget: Budget? = null
    private var pendingBudgetOperationId: String? = null

    private var activityJob: Job? = null
    private var activityRequest = 0

    init {
        viewModelScope.launch {
            combine(auth.session, reload) { session, _ -> session }
                .collectLatest { session ->
                    val epoch = ++generation
                    sessionScope?.cancel()
                    activityJob?.cancel()
                    pendingAccount = null
                    pendingExpense = null
                    drafts = null
                    draftRevision = 0L
                    sessionScope = null
                    repository = null
                    activityJob = null
                    activityRequest++

                    if (session !is AuthSession.SignedIn) {
                        pendingAccount = null
                        pendingExpense = null
                        _state.value = FinanceUiState(loading = false)
                        return@collectLatest
                    }

                    val uid = session.user.uid
                    val previous = _state.value.takeIf { it.userId == uid }

                    if (previous == null) {
                        pendingAccount = null
                        pendingExpense = null
                    }

                    _state.value = FinanceUiState(
                        userId = uid,
                        accountForm = previous?.accountForm?.copy(busy = false)
                            ?: CashAccountForm(),
                        expenseForm = previous?.expenseForm?.copy(busy = false)
                            ?: ExpenseForm(),
                    )

                    try {
                        coroutineScope {
                            sessionScope = this

                            val current = factory.current()
                            check(current.userId == uid)
                            repository = current

                            val cachedProfile = current.cachedProfile()
                            ensureActive()
                            if (epoch != generation) return@coroutineScope
                            zone = cachedProfile?.timeZone ?: ZoneId.of("Asia/Colombo")

                            val controller = FinanceDraftController(
                                uid = uid,
                                local = local,
                                state = _state,
                                scope = this,
                                nextRevision = { ++draftRevision },
                            )
                            drafts = controller

                            draftRevision = maxOf(
                                draftRevision,
                                controller.initialize(today()),
                            )
                            ensureActive()
                            if (epoch != generation) return@coroutineScope
                            controller.startWatching()

                            launch {
                                try {
                                    when (val profile = current.ensureProfile()) {
                                        is CommandResult.Accepted -> {
                                            if (epoch == generation) {
                                                zone = profile.value.timeZone
                                                current.ensureBasicCategories()
                                            }
                                        }

                                        is CommandResult.Rejected -> Unit
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Cached references and drafts remain usable.
                                }
                            }

                            launch {
                                try {
                                    current.reconcileExpenseReceipts()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Another attempt occurs when server snapshots return or the user retries.
                                }
                            }

                            var observedLedger: String? = null

                            combine(
                                current.observeAccounts(),
                                current.observeEffectiveLedger(),
                                combine(current.observeCategories(), current.observeBudgets(), ::Pair),
                            ) { accounts, ledger, (categories, budgets) ->
                                Triple(accounts, ledger, categories to budgets)
                            }.collect { (accounts, ledger, catAndBud) ->
                                if (epoch != generation) return@collect
                                val (categories, budgets) = catAndBud

                                val accountData = accounts as? Observation.Data
                                val categoryData = categories as? Observation.Data
                                val budgetData = budgets as? Observation.Data

                                _state.update { old ->
                                    val availableAccounts = accountData?.value ?: old.accounts
                                    val availableCategories = categoryData?.value ?: old.categories
                                    val availableBudgets = budgetData?.value ?: old.budgets
                                    val selectableAccounts = availableAccounts.filter {
                                        it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null
                                    }
                                    val selectableCategories = availableCategories.filter {
                                        it.metadata.archivedAt == null
                                    }

                                    var form = old.expenseForm

                                    if (!form.locked && !form.saved) {
                                        val accountId = form.accountId?.takeIf { id ->
                                            selectableAccounts.any { it.id == id }
                                        } ?: selectableAccounts.firstOrNull()?.id

                                        val category = selectableCategories.firstOrNull {
                                            it.id == form.categoryId
                                        } ?: selectableCategories.firstOrNull()

                                        form = form.copy(
                                            accountId = accountId,
                                            categoryId = category?.id,
                                            classification = if (category?.id != form.categoryId) {
                                                category?.defaultCommitmentClass
                                                    ?: CommitmentClass.DISCRETIONARY
                                            } else {
                                                form.classification
                                            },
                                        )
                                    }

                                    old.copy(
                                        accounts = availableAccounts,
                                        categories = availableCategories,
                                        budgets = availableBudgets,
                                        referencesReady = accountData != null && categoryData != null,
                                        expenseForm = form,
                                    )
                                }

                                val ledgerData = ledger as? Observation.Data

                                val completeAccounts = accountData?.metadata?.completeness ==
                                    HistoryCompleteness.COMPLETE

                                if (accountData != null && ledgerData != null && completeAccounts) {
                                    try {
                                        val balances = accountData.value.associate { account ->
                                            check(
                                                ledgerData.value.events.any {
                                                    it.id == account.openingTransactionId
                                                },
                                            )
                                            account.id to accountBalance(account, ledgerData.value)
                                        }

                                        val pool = accountData.value
                                            .filter { it.includeInEverydayPool }
                                            .fold(0L) { total, account ->
                                                val units = balances.getValue(account.id).units
                                                require(
                                                    units is BalanceUnits.Fiat &&
                                                        units.asset == FiatCurrency.LKR,
                                                )
                                                Math.addExact(total, units.minor)
                                            }

                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                error = null,
                                                balances = balances,
                                                poolBalanceMinor = pool,
                                                cached = accountData.metadata.origin == DataOrigin.CACHE ||
                                                    ledgerData.metadata.origin == DataOrigin.CACHE,
                                                lastConfirmedAt = ledgerData.metadata.observedAt?.toString(),
                                                sdkPendingIds = ledgerData.metadata.pendingOperationIds,
                                            )
                                        }
                                    } catch (_: Exception) {
                                        failLoading(
                                            epoch,
                                            "Complete account balances are not available yet.",
                                        )
                                    }
                                } else {
                                    val failure = listOfNotNull(
                                        (accounts as? Observation.Failed)?.failure,
                                        (ledger as? Observation.Failed)?.failure,
                                        (categories as? Observation.Failed)?.failure,
                                    ).firstOrNull()

                                    if (failure != null ||
                                        (accountData != null && !completeAccounts)
                                    ) {
                                        failLoading(
                                            epoch,
                                            failure?.message
                                                ?: "Cached account history is incomplete. Connect to refresh balances.",
                                        )
                                    }
                                }

                                if (ledgerData != null) {
                                    val signature = ledgerData.value.events.map { it.id }
                                        .sorted().joinToString("|") +
                                        "|${ledgerData.metadata.origin}|${ledgerData.metadata.writeState}"

                                    if (signature != observedLedger) {
                                        observedLedger = signature
                                        loadActivity(reset = true)
                                    }

                                    if (ledgerData.metadata.origin == DataOrigin.SERVER) {
                                        launch {
                                            try {
                                                current.reconcileExpenseReceipts()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                // Preserve unresolved receipts for a later check.
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failLoading(
                            epoch,
                            "Unable to load your financial data. Please retry.",
                        )
                    } finally {
                        if (epoch == generation) {
                            sessionScope = null
                            repository = null
                        }
                    }
                }
        }
    }

    fun onEvent(userId: String, event: FinanceEvent) {
        if (_state.value.userId != userId) return

        when (event) {
            FinanceEvent.RetryConnection -> {
                val epoch = generation
                val controller = drafts
                viewModelScope.launch {
                    try {
                        controller?.flush()
                        if (epoch == generation && _state.value.userId == userId) {
                            reload.update { it + 1 }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (epoch == generation && _state.value.userId == userId) {
                            _state.update {
                                it.copy(localError = "Unable to retain the current draft.")
                            }
                        }
                    }
                }
            }

            is FinanceEvent.AccountNameChanged ->
                editAccount { it.copy(name = event.value) }

            is FinanceEvent.OpeningAmountChanged ->
                editAccount { it.copy(opening = event.value) }

            is FinanceEvent.AccountDateChanged ->
                editAccount { it.copy(date = event.value) }

            FinanceEvent.CreateAccount -> createAccount()

            FinanceEvent.NewAccount -> {
                if (_state.value.accountForm.saved) {
                    pendingAccount = null
                    _state.update {
                        it.copy(accountForm = CashAccountForm(date = today()))
                    }
                }
            }

            is FinanceEvent.ExpenseAccountChanged ->
                editExpense { it.copy(accountId = event.id) }

            is FinanceEvent.ExpenseCategoryChanged -> {
                val category = _state.value.selectableCategories
                    .firstOrNull { it.id == event.id } ?: return

                editExpense {
                    it.copy(
                        categoryId = category.id,
                        classification = category.defaultCommitmentClass,
                    )
                }
            }

            is FinanceEvent.ExpenseAmountChanged ->
                editExpense { it.copy(amount = event.value) }

            is FinanceEvent.ExpenseDateChanged ->
                editExpense { it.copy(date = event.value) }

            is FinanceEvent.ExpenseNoteChanged ->
                editExpense { it.copy(note = event.value) }

            is FinanceEvent.ExpenseClassChanged ->
                editExpense { it.copy(classification = event.value) }

            FinanceEvent.ClearExpenseDraft -> {
                val form = _state.value.expenseForm

                if (!form.busy && !form.locked) {
                    if (form.writeState == WriteState.REJECTED) {
                        pendingExpense?.operationId?.let {
                            drafts?.acknowledgeRejected(it)
                        }
                    }

                    pendingExpense = null

                    _state.update {
                        val category = it.selectableCategories.firstOrNull()
                        it.copy(
                            expenseForm = ExpenseForm(
                                accountId = it.selectableAccounts.firstOrNull()?.id,
                                categoryId = category?.id,
                                classification = category?.defaultCommitmentClass
                                    ?: CommitmentClass.DISCRETIONARY,
                                date = today(),
                            ),
                        )
                    }
                }
            }

            is FinanceEvent.ReviewRejectedExpense ->
                drafts?.reviewRejected(event.operationId)

            FinanceEvent.SaveExpense -> saveExpense()

            FinanceEvent.NewExpense -> {
                if (_state.value.expenseForm.saved) {
                    pendingExpense = null
                    _state.update {
                        val category = it.selectableCategories.firstOrNull()
                        it.copy(
                            expenseForm = ExpenseForm(
                                accountId = it.selectableAccounts.firstOrNull()?.id,
                                categoryId = category?.id,
                                classification = category?.defaultCommitmentClass
                                    ?: CommitmentClass.DISCRETIONARY,
                                date = today(),
                            ),
                        )
                    }
                }
            }

            is FinanceEvent.CategoryNameChanged ->
                _state.update {
                    it.copy(categoryForm = it.categoryForm.copy(name = event.value, error = null))
                }

            is FinanceEvent.CategoryClassChanged ->
                _state.update {
                    it.copy(categoryForm = it.categoryForm.copy(classification = event.value, error = null))
                }

            is FinanceEvent.CategoryIconChanged ->
                _state.update {
                    it.copy(categoryForm = it.categoryForm.copy(iconKey = event.value, error = null))
                }

            is FinanceEvent.ToggleCategoryDialog ->
                _state.update {
                    it.copy(
                        showCategoryDialog = event.show,
                        categoryForm = if (event.show) CategoryForm() else it.categoryForm,
                    )
                }

            FinanceEvent.SaveCategory -> saveCustomCategory()

            is FinanceEvent.OpenVoidDialog ->
                _state.update {
                    it.copy(
                        showVoidDialog = true,
                        voidForm = VoidExpenseForm(transactionId = event.transactionId),
                    )
                }

            is FinanceEvent.VoidReasonChanged ->
                _state.update {
                    it.copy(voidForm = it.voidForm.copy(reason = event.value, error = null))
                }

            is FinanceEvent.ToggleVoidDialog ->
                _state.update {
                    it.copy(
                        showVoidDialog = event.show,
                        voidForm = if (event.show) it.voidForm else VoidExpenseForm(),
                    )
                }

            FinanceEvent.SubmitVoidExpense -> submitVoidExpense()

            is FinanceEvent.BudgetScopeChanged -> {
                pendingBudget = null
                pendingBudgetOperationId = null
                _state.update {
                    val defaultCatId = if (event.scope == BudgetScope.CATEGORY_DISCRETIONARY) {
                        it.budgetForm.categoryId ?: it.selectableCategories.firstOrNull()?.id
                    } else null
                    it.copy(
                        budgetForm = it.budgetForm.copy(
                            scope = event.scope,
                            categoryId = defaultCatId,
                            error = null,
                        ),
                    )
                }
            }

            is FinanceEvent.BudgetPeriodChanged -> {
                pendingBudget = null
                pendingBudgetOperationId = null
                _state.update {
                    it.copy(budgetForm = it.budgetForm.copy(periodType = event.period, error = null))
                }
            }

            is FinanceEvent.BudgetCategoryChanged -> {
                pendingBudget = null
                pendingBudgetOperationId = null
                _state.update {
                    it.copy(budgetForm = it.budgetForm.copy(categoryId = event.categoryId, error = null))
                }
            }

            is FinanceEvent.BudgetLimitChanged -> {
                pendingBudget = null
                pendingBudgetOperationId = null
                _state.update {
                    it.copy(budgetForm = it.budgetForm.copy(limitAmount = event.amount, error = null))
                }
            }

            is FinanceEvent.ToggleBudgetDialog -> {
                if (!event.show) {
                    pendingBudget = null
                    pendingBudgetOperationId = null
                }
                _state.update {
                    val defaultCatId = it.selectableCategories.firstOrNull()?.id
                    it.copy(
                        showBudgetDialog = event.show,
                        budgetForm = if (event.show) {
                            BudgetForm(categoryId = defaultCatId)
                        } else {
                            it.budgetForm
                        },
                    )
                }
            }

            FinanceEvent.SubmitBudget -> submitBudget()

            FinanceEvent.ReloadActivity -> loadActivity(reset = true)

            FinanceEvent.LoadMoreActivity -> {
                if (!_state.value.activityLoading &&
                    _state.value.nextActivityCursor != null
                ) {
                    loadActivity(reset = false)
                }
            }
        }
    }

    fun flushAndSignOut(
        userId: String,
        onSignOut: () -> Unit,
    ) {
        val epoch = generation
        val controller = drafts
        viewModelScope.launch {
            try {
                controller?.flush()

                if (epoch == generation && _state.value.userId == userId) {
                    onSignOut()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation && _state.value.userId == userId) {
                    _state.update {
                        it.copy(localError = "Unable to retain drafts. Please retry sign out.")
                    }
                }
            }
        }
    }

    private fun editAccount(change: (CashAccountForm) -> CashAccountForm) {
        _state.update {
            if (it.accountForm.busy || it.accountForm.locked || it.accountForm.saved) {
                it
            } else {
                it.copy(accountForm = change(it.accountForm).copy(error = null))
            }
        }
    }

    private fun editExpense(change: (ExpenseForm) -> ExpenseForm) {
        if (_state.value.expenseForm.writeState == WriteState.REJECTED) {
            pendingExpense?.operationId?.let {
                drafts?.acknowledgeRejected(it)
            }
            pendingExpense = null
        }

        _state.update {
            if (it.expenseForm.busy || it.expenseForm.locked || it.expenseForm.saved) {
                it
            } else {
                it.copy(
                    expenseForm = change(it.expenseForm).copy(
                        error = null,
                        writeState = null,
                    ),
                )
            }
        }
    }

    private fun today(): String = LocalDate.now(zone).toString()

    private fun financialTime(dateText: String, now: Instant): FinancialTime {
        val date = LocalDate.parse(dateText.trim())
        val localToday = now.atZone(zone).toLocalDate()
        require(!date.isAfter(localToday)) { "Use today or an earlier date." }

        val occurredAt = if (date == localToday) {
            now
        } else {
            date.atStartOfDay(zone).toInstant()
        }

        return FinancialTime(occurredAt, date, zone)
    }

    private fun createAccount() {
        val current = _state.value
        if (!current.ready || current.cached || current.accountForm.busy || current.accountForm.saved) {
            return
        }

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation
        val controller = drafts ?: return

        val command = pendingAccount ?: try {
            val draft = current.accountForm
            require(draft.name.isNotBlank()) { "Enter an account name." }

            val opening = Money.parse(draft.opening.trim(), FiatCurrency.LKR)
            val now = Instant.now()
            val time = financialTime(draft.date, now)
            val operationId = UUID.randomUUID().toString()

            CreateAccountCommand(
                operationId = operationId,
                account = Account(
                    id = UUID.randomUUID().toString(),
                    name = draft.name.trim(),
                    type = AccountType.CASH,
                    asset = FiatCurrency.LKR,
                    includeInEverydayPool = true,
                    openingTransactionId = operationId,
                    openedOn = time.occurredLocalDate,
                ),
                openingAmount = NativeAmount.Fiat(opening),
                financialTime = time,
                clientCreatedAt = now,
            )
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    accountForm = it.accountForm.copy(
                        error = "Enter a name, a valid LKR amount with at most " +
                            "two decimal places, and a date no later than today.",
                    ),
                )
            }
            return
        }

        pendingAccount = command
        _state.update {
            it.copy(
                accountForm = it.accountForm.copy(
                    busy = true,
                    locked = true,
                    error = null,
                ),
            )
        }

        scope.launch {
            try {
                controller.flush()
                ensureActive()
                if (epoch != generation) return@launch
                val result = repo.createWithOpening(command)
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        pendingAccount = null
                        _state.update {
                            it.copy(
                                accountForm = it.accountForm.copy(
                                    busy = false,
                                    locked = true,
                                    saved = true,
                                    error = null,
                                ),
                            )
                        }
                    }

                    is CommandResult.Rejected -> {
                        val uncertain = result.failure.code in setOf(
                            FailureCode.NETWORK,
                            FailureCode.REJECTED,
                        )
                        if (!uncertain) pendingAccount = null

                        _state.update {
                            it.copy(
                                accountForm = it.accountForm.copy(
                                    busy = false,
                                    locked = uncertain,
                                    error = result.failure.message,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update {
                        it.copy(
                            accountForm = it.accountForm.copy(
                                busy = false,
                                locked = true,
                                error = "Save not confirmed. Reconnect and retry.",
                            ),
                        )
                    }
                }
            } finally {
                if (epoch == generation) {
                    try {
                        controller.flush()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (epoch == generation) {
                            _state.update {
                                it.copy(localError = "Unable to retain the latest account draft.")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveExpense() {
        val current = _state.value
        if (!current.canRecordExpense || current.expenseForm.busy || current.expenseForm.saved) {
            return
        }

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation
        val controller = drafts ?: return

        val command = pendingExpense ?: try {
            val draft = current.expenseForm

            val accountId = requireNotNull(draft.accountId)
            val categoryId = requireNotNull(draft.categoryId)
            require(current.selectableAccounts.any { it.id == accountId })
            require(current.selectableCategories.any { it.id == categoryId })
            require(draft.note.length <= 1000)

            val amount = EntryAmount(
                Money.parse(draft.amount.trim(), FiatCurrency.LKR),
            )
            val now = Instant.now()

            RecordExpenseCommand(
                operationId = UUID.randomUUID().toString(),
                accountId = accountId,
                amount = amount,
                categoryId = categoryId,
                commitmentClass = draft.classification,
                financialTime = financialTime(draft.date, now),
                clientCreatedAt = now,
                note = draft.note.trim().takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    expenseForm = it.expenseForm.copy(
                        error = "Select an account and category, enter a positive " +
                            "LKR amount with at most two decimal places, and use " +
                            "a date no later than today. Notes allow 1000 characters.",
                    ),
                )
            }
            return
        }

        pendingExpense = command
        _state.update {
            it.copy(
                expenseForm = it.expenseForm.copy(
                    busy = true,
                    locked = true,
                    error = null,
                ),
            )
        }

        scope.launch {
            try {
                controller.flush()
                ensureActive()
                if (epoch != generation) return@launch
                val result = repo.recordExpense(command)
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        // Keep the frozen request until “Record another expense”.
                        // The durable receipt determines pending/confirmed/rejected status.
                        controller.refreshReceipts()
                    }

                    is CommandResult.Rejected -> {
                        val uncertain = result.failure.code in setOf(
                            FailureCode.NETWORK,
                            FailureCode.REJECTED,
                        )
                        if (!uncertain) pendingExpense = null

                        _state.update {
                            it.copy(
                                expenseForm = it.expenseForm.copy(
                                    busy = false,
                                    locked = uncertain,
                                    error = result.failure.message,
                                ),
                            )
                        }
                        controller.refreshReceipts()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update {
                        it.copy(
                            expenseForm = it.expenseForm.copy(
                                busy = false,
                                locked = true,
                                error = "Save not confirmed. Reconnect and retry.",
                            ),
                        )
                    }
                }
            } finally {
                if (epoch == generation) {
                    _state.update {
                        it.copy(expenseForm = it.expenseForm.copy(busy = false))
                    }
                    try {
                        controller.flush()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (epoch == generation) {
                            _state.update {
                                it.copy(localError = "Unable to retain the latest expense draft.")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadActivity(reset: Boolean) {
        val repo = repository ?: return
        val scope = sessionScope ?: return
        if (!_state.value.draftsLoaded) return

        val cursor = if (reset) null else _state.value.nextActivityCursor
        if (!reset && cursor == null) return

        val epoch = generation
        val request = ++activityRequest
        activityJob?.cancel()

        _state.update {
            it.copy(activityLoading = true, activityError = null)
        }

        activityJob = scope.launch {
            try {
                val result = repo.activityPage(ActivityQuery(cursor = cursor))
                if (epoch != generation || request != activityRequest) return@launch

                when (result) {
                    is Observation.Data -> {
                        _state.update {
                            it.copy(
                                activity = if (reset) {
                                    result.value.events
                                } else {
                                    (it.activity + result.value.events).distinctBy { event ->
                                        event.id
                                    }
                                },
                                nextActivityCursor = result.value.nextCursor,
                                activityLoading = false,
                                activityError = null,
                            )
                        }
                    }

                    is Observation.Failed -> {
                        if (!reset && result.failure.code == FailureCode.CONFLICT) {
                            loadActivity(reset = true)
                        } else {
                            _state.update {
                                it.copy(
                                    activityLoading = false,
                                    activityError = result.failure.message,
                                )
                            }
                        }
                    }

                    Observation.Loading -> {
                        _state.update { it.copy(activityLoading = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation && request == activityRequest) {
                    _state.update {
                        it.copy(
                            activityLoading = false,
                            activityError = "Unable to load Activity. Please retry.",
                        )
                    }
                }
            }
        }
    }

    private fun saveCustomCategory() {
        val current = _state.value
        if (!current.ready || current.categoryForm.busy) return

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation

        val draft = current.categoryForm
        val name = draft.name.trim()
        if (name.isBlank() || name.length > 80) {
            _state.update {
                it.copy(
                    categoryForm = it.categoryForm.copy(
                        error = "Enter a valid category name (up to 80 characters).",
                    ),
                )
            }
            return
        }

        if (current.selectableCategories.any { it.name.equals(name, ignoreCase = true) }) {
            _state.update {
                it.copy(
                    categoryForm = it.categoryForm.copy(
                        error = "A category with this name already exists.",
                    ),
                )
            }
            return
        }

        _state.update {
            it.copy(
                categoryForm = it.categoryForm.copy(
                    busy = true,
                    error = null,
                ),
            )
        }

        val newCategoryId = UUID.randomUUID().toString()
        val category = Category(
            id = newCategoryId,
            name = name,
            defaultCommitmentClass = draft.classification,
            iconKey = draft.iconKey.trim().takeIf { it.isNotEmpty() },
            sortOrder = (current.categories.maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )

        val operationId = UUID.randomUUID().toString()

        scope.launch {
            try {
                ensureActive()
                if (epoch != generation) return@launch
                val result = repo.saveCategory(operationId, category)
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        _state.update { old ->
                            val updatedExpenseForm = old.expenseForm.copy(
                                categoryId = category.id,
                                classification = category.defaultCommitmentClass,
                            )
                            old.copy(
                                showCategoryDialog = false,
                                categoryForm = CategoryForm(),
                                expenseForm = updatedExpenseForm,
                            )
                        }
                    }

                    is CommandResult.Rejected -> {
                        _state.update {
                            it.copy(
                                categoryForm = it.categoryForm.copy(
                                    busy = false,
                                    error = result.failure.message,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update {
                        it.copy(
                            categoryForm = it.categoryForm.copy(
                                busy = false,
                                error = "Unable to save category. Connect and retry.",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun submitVoidExpense() {
        val current = _state.value
        if (!current.ready || current.voidForm.busy) return

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation

        val form = current.voidForm
        val transactionId = form.transactionId ?: return
        val reason = form.reason.trim()

        if (reason.isBlank() || reason.length > 1000) {
            _state.update {
                it.copy(
                    voidForm = it.voidForm.copy(
                        error = "Enter a valid reason for voiding this expense (up to 1000 characters).",
                    ),
                )
            }
            return
        }

        _state.update {
            it.copy(voidForm = it.voidForm.copy(busy = true, error = null))
        }

        val operationId = UUID.randomUUID().toString()

        scope.launch {
            try {
                ensureActive()
                if (epoch != generation) return@launch
                val result = repo.voidExpense(
                    operationId = operationId,
                    transactionId = transactionId,
                    voidReason = reason,
                )
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        _state.update {
                            it.copy(
                                showVoidDialog = false,
                                voidForm = VoidExpenseForm(),
                            )
                        }
                        loadActivity(reset = true)
                    }

                    is CommandResult.Rejected -> {
                        _state.update {
                            it.copy(
                                voidForm = it.voidForm.copy(
                                    busy = false,
                                    error = result.failure.message,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update {
                        it.copy(
                            voidForm = it.voidForm.copy(
                                busy = false,
                                error = "Unable to void expense. Connect and retry.",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun submitBudget() {
        val current = _state.value
        if (!current.ready || current.budgetForm.busy) return

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation

        val form = current.budgetForm
        val rawAmount = form.limitAmount.trim()

        val parsedMoney = try {
            val money = Money.parse(rawAmount, FiatCurrency.LKR)
            require(money.minor > 0L) { "Spending limit must be greater than zero." }
            EntryAmount(money)
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    budgetForm = it.budgetForm.copy(
                        error = "Enter a positive LKR amount with at most two decimal places.",
                    ),
                )
            }
            return
        }

        if (form.scope == BudgetScope.CATEGORY_DISCRETIONARY && form.categoryId.isNullOrBlank()) {
            _state.update {
                it.copy(
                    budgetForm = it.budgetForm.copy(
                        error = "Select a category for category spending limits.",
                    ),
                )
            }
            return
        }

        val categoryId = if (form.scope == BudgetScope.CATEGORY_DISCRETIONARY) form.categoryId else null

        // Check if form parameters match current pending budget
        val isPendingReuse = pendingBudget?.let { pending ->
            pending.scope == form.scope &&
                pending.periodType == form.periodType &&
                pending.categoryId == categoryId &&
                pending.limit == parsedMoney
        } ?: false

        // Safeguard: Check if an active budget already exists for this exact slot (ignoring our pending budget)
        val today = LocalDate.now(zone)
        val isSlotOccupied = current.budgets.any {
            it.id != pendingBudget?.id &&
                it.state == BudgetState.ACTIVE &&
                it.scope == form.scope &&
                it.periodType == form.periodType &&
                it.categoryId == categoryId &&
                !it.effectiveFrom.isAfter(today) &&
                (it.effectiveTo == null || !it.effectiveTo.isBefore(today))
        }

        if (isSlotOccupied) {
            _state.update {
                it.copy(
                    budgetForm = it.budgetForm.copy(
                        error = "An active budget already exists for this scope and period.",
                    ),
                )
            }
            return
        }

        val (budget, operationId) = if (isPendingReuse && pendingBudget != null && pendingBudgetOperationId != null) {
            pendingBudget!! to pendingBudgetOperationId!!
        } else {
            val newBudgetId = UUID.randomUUID().toString()
            val newOpId = UUID.randomUUID().toString()
            val newBudget = Budget(
                id = newBudgetId,
                scope = form.scope,
                periodType = form.periodType,
                limit = parsedMoney,
                effectiveFrom = today,
                categoryId = categoryId,
                rolloverEnabled = false,
                state = BudgetState.ACTIVE,
            )
            pendingBudget = newBudget
            pendingBudgetOperationId = newOpId
            newBudget to newOpId
        }

        _state.update {
            it.copy(budgetForm = it.budgetForm.copy(busy = true, error = null))
        }

        scope.launch {
            try {
                ensureActive()
                if (epoch != generation) return@launch
                val result = repo.saveBudget(operationId, budget)
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        pendingBudget = null
                        pendingBudgetOperationId = null
                        _state.update {
                            it.copy(
                                showBudgetDialog = false,
                                budgetForm = BudgetForm(),
                            )
                        }
                    }

                    is CommandResult.Rejected -> {
                        val uncertain = result.failure.code == FailureCode.NETWORK
                        if (!uncertain) {
                            pendingBudget = null
                            pendingBudgetOperationId = null
                        }

                        _state.update {
                            it.copy(
                                budgetForm = it.budgetForm.copy(
                                    busy = false,
                                    error = result.failure.message,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update {
                        it.copy(
                            budgetForm = it.budgetForm.copy(
                                busy = false,
                                error = "Unable to save budget. Connect and retry.",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun failLoading(epoch: Int, message: String) {
        if (epoch == generation) {
            _state.update {
                it.copy(
                    loading = false,
                    error = message,
                    balances = emptyMap(),
                    poolBalanceMinor = null,
                )
            }
        }
    }
}
