package com.pocketpath.feature.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private val _state = MutableStateFlow(FinanceUiState())
    val state = _state.asStateFlow()

    private val reload = MutableStateFlow(0)

    private var generation = 0
    private var repository: FirebaseFoundationRepository? = null
    private var sessionScope: CoroutineScope? = null
    private var zone = ZoneId.of("Asia/Colombo")

    private var pendingAccount: CreateAccountCommand? = null
    private var pendingExpense: RecordExpenseCommand? = null

    private var activityJob: Job? = null
    private var activityRequest = 0

    init {
        viewModelScope.launch {
            combine(auth.session, reload) { session, _ -> session }
                .collectLatest { session ->
                    val epoch = ++generation
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

                            when (val profile = current.ensureProfile()) {
                                is CommandResult.Rejected -> {
                                    failLoading(epoch, profile.failure.message)
                                    return@coroutineScope
                                }

                                is CommandResult.Accepted -> {
                                    zone = profile.value.timeZone
                                }
                            }

                            val today = today()
                            _state.update {
                                it.copy(
                                    accountForm = it.accountForm.copy(
                                        date = it.accountForm.date.ifBlank { today },
                                    ),
                                    expenseForm = it.expenseForm.copy(
                                        date = it.expenseForm.date.ifBlank { today },
                                    ),
                                )
                            }

                            when (val categories = current.ensureBasicCategories()) {
                                is CommandResult.Rejected -> {
                                    failLoading(epoch, categories.failure.message)
                                    return@coroutineScope
                                }

                                is CommandResult.Accepted -> Unit
                            }

                            var observedLedger: String? = null

                            combine(
                                current.observeAccounts(),
                                current.observeEffectiveLedger(),
                                current.observeCategories(),
                            ) { accounts, ledger, categories ->
                                Triple(accounts, ledger, categories)
                            }.collect { (accounts, ledger, categories) ->
                                if (epoch != generation) return@collect

                                val failure = listOfNotNull(
                                    (accounts as? Observation.Failed)?.failure,
                                    (ledger as? Observation.Failed)?.failure,
                                    (categories as? Observation.Failed)?.failure,
                                ).firstOrNull()

                                if (failure != null) {
                                    failLoading(epoch, failure.message)
                                } else if (
                                    accounts is Observation.Data &&
                                    ledger is Observation.Data &&
                                    categories is Observation.Data
                                ) {
                                    try {
                                        val balances = accounts.value.associate { account ->
                                            check(
                                                ledger.value.events.any {
                                                    it.id == account.openingTransactionId
                                                },
                                            ) {
                                                "Waiting for the account opening balance."
                                            }

                                            account.id to accountBalance(account, ledger.value)
                                        }

                                        val pool = accounts.value
                                            .filter { it.includeInEverydayPool }
                                            .fold(0L) { total, account ->
                                                val units = balances.getValue(account.id).units
                                                require(
                                                    units is BalanceUnits.Fiat &&
                                                        units.asset == FiatCurrency.LKR,
                                                )
                                                Math.addExact(total, units.minor)
                                            }

                                        _state.update { old ->
                                            val availableAccounts = accounts.value.filter {
                                                it.asset == FiatCurrency.LKR &&
                                                    it.metadata.archivedAt == null
                                            }
                                            val availableCategories = categories.value.filter {
                                                it.metadata.archivedAt == null
                                            }

                                            var draft = old.expenseForm

                                            if (!draft.locked && !draft.saved) {
                                                val accountId = draft.accountId
                                                    ?.takeIf { id ->
                                                        availableAccounts.any { it.id == id }
                                                    }
                                                    ?: availableAccounts.firstOrNull()?.id

                                                val selectedCategory = availableCategories
                                                    .firstOrNull { it.id == draft.categoryId }
                                                    ?: availableCategories.firstOrNull()

                                                draft = draft.copy(
                                                    accountId = accountId,
                                                    categoryId = selectedCategory?.id,
                                                    classification = if (
                                                        selectedCategory?.id != draft.categoryId
                                                    ) {
                                                        selectedCategory?.defaultCommitmentClass
                                                            ?: CommitmentClass.DISCRETIONARY
                                                    } else {
                                                        draft.classification
                                                    },
                                                )
                                            }

                                            old.copy(
                                                loading = false,
                                                error = null,
                                                accounts = accounts.value,
                                                categories = categories.value,
                                                balances = balances,
                                                poolBalanceMinor = pool,
                                                expenseForm = draft,
                                            )
                                        }

                                        val signature = ledger.value.events
                                            .map { it.id }
                                            .sorted()
                                            .joinToString("|")

                                        if (signature != observedLedger) {
                                            observedLedger = signature
                                            loadActivity(reset = true)
                                        }
                                    } catch (_: Exception) {
                                        failLoading(
                                            epoch,
                                            "Complete account balances are not available yet.",
                                        )
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
            FinanceEvent.RetryConnection -> reload.update { it + 1 }

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
        _state.update {
            if (it.expenseForm.busy || it.expenseForm.locked || it.expenseForm.saved) {
                it
            } else {
                it.copy(expenseForm = change(it.expenseForm).copy(error = null))
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
        if (!current.ready || current.accountForm.busy || current.accountForm.saved) {
            return
        }

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation

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
            }
        }
    }

    private fun saveExpense() {
        val current = _state.value
        if (!current.ready || current.expenseForm.busy || current.expenseForm.saved) {
            return
        }

        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation

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
                val result = repo.recordExpense(command)
                if (epoch != generation) return@launch

                when (result) {
                    is CommandResult.Accepted -> {
                        pendingExpense = null
                        _state.update {
                            it.copy(
                                expenseForm = it.expenseForm.copy(
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
            }
        }
    }

    private fun loadActivity(reset: Boolean) {
        val repo = repository ?: return
        val scope = sessionScope ?: return
        if (!_state.value.ready) return

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
