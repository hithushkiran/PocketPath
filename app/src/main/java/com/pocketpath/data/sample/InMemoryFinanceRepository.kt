package com.pocketpath.data.sample

import com.pocketpath.domain.calculation.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * Explicit development-only instance. No Hilt binding, default UID, disk, cloud confirmation or auth.
 * One atomic snapshot prevents observers from seeing an account without its opening event.
 * Everything, including retry identities, is lost when this instance/process is discarded.
 */
class InMemoryFinanceRepository(
    override val userId: String,
    private val clock: Clock,
    initialCategories: List<Category> = emptyList(),
) : AccountRepository, LedgerRepository, CategoryRepository, BudgetRepository {
    private data class Snapshot(
        val accounts: Map<String, Account> = emptyMap(),
        val events: Map<String, FinancialTransaction> = emptyMap(),
        val categories: Map<String, Category>,
        val budgets: Map<String, Budget> = emptyMap(),
        val revision: Long = 0,
    )
    private sealed interface Applied {
        data class AccountCreated(val command: CreateAccountCommand, val value: Account) : Applied
        data class ExpenseRecorded(val command: RecordExpenseCommand, val value: FinancialTransaction) : Applied
        data class CategorySaved(val category: Category) : Applied
        data class BudgetSaved(val budget: Budget) : Applied
        data class ExpenseVoided(val value: FinancialTransaction) : Applied
    }
    private val mutex = Mutex()
    private val applied = mutableMapOf<String, Applied>()
    private val state = MutableStateFlow(Snapshot(categories = initialCategories.associateBy { it.id }))
    init {
        require(userId.isNotBlank())
        require(initialCategories.map { it.id }.distinct().size == initialCategories.size)
    }

    private fun metadata(history: Boolean = false) = ObservationMetadata(
        DataOrigin.MEMORY_SAMPLE,
        if (history) HistoryCompleteness.COMPLETE else HistoryCompleteness.NOT_APPLICABLE,
        WriteState.LOCAL_SAVED,
        clock.instant(),
    )
    private fun <T> data(value: T, history: Boolean = false) = Observation.Data(value, metadata(history))
    private fun failure(code: FailureCode, message: String, id: String? = null) = RepositoryFailure(code, message, id)
    private fun conflict(id: String) = CommandResult.Rejected(failure(FailureCode.CONFLICT, "Operation ID reused with conflicting content", id))
    private fun invalid(id: String, error: Exception) = CommandResult.Rejected(failure(FailureCode.VALIDATION, error.message ?: "Invalid command", id))
    private fun checkId(id: String) { require(id.isNotBlank() && '/' !in id) }

    override fun observeAccounts(): Flow<Observation<List<Account>>> = state.map { data(it.accounts.values.sortedBy(Account::id)) }
    override suspend fun findAccount(accountId: String): Observation<Account?> = data(state.value.accounts[accountId])
    override fun observeCategories(): Flow<Observation<List<Category>>> = state.map { data(it.categories.values.sortedWith(compareBy(Category::sortOrder, Category::id))) }
    override fun observeEffectiveLedger(): Flow<Observation<CompleteLedger>> = state.map {
        data(CompleteLedger(it.events.values.filter { e -> e.status == TransactionStatus.POSTED }), true)
    }
    override fun observeAccountHistory(accountId: String): Flow<Observation<CompleteLedger>> = state.map {
        if (accountId !in it.accounts) Observation.Failed(failure(FailureCode.NOT_FOUND, "Account not found"))
        else data(CompleteLedger(it.events.values.filter { e -> e.status == TransactionStatus.POSTED && accountId in e.accountIds }, LedgerScope.AccountEvents(accountId)), true)
    }
    override fun observeBalance(accountId: String): Flow<Observation<AccountBalance>> = state.map {
        val account = it.accounts[accountId]
        if (account == null) Observation.Failed(failure(FailureCode.NOT_FOUND, "Account not found"))
        else try {
            data(accountBalance(account, CompleteLedger(it.events.values.toList())), true)
        } catch (error: ArithmeticException) {
            Observation.Failed(failure(FailureCode.VALIDATION, error.message ?: "Balance overflow"))
        }
    }

    override suspend fun createWithOpening(command: CreateAccountCommand): CommandResult<Account> = mutex.withLock {
        val previous = applied[command.operationId]
        if (previous != null) return@withLock if (previous is Applied.AccountCreated && previous.command == command)
            CommandResult.Accepted(previous.value, WriteState.LOCAL_SAVED, true) else conflict(command.operationId)
        try {
            checkId(command.operationId)
            val account = command.account
            checkId(account.id)
            require(account.id !in state.value.accounts && command.operationId !in state.value.events) { "Account or opening event already exists" }
            require(account.openingTransactionId == command.operationId && account.openedOn == command.financialTime.occurredLocalDate)
            require(account.asset == command.openingAmount.asset && account.metadata.archivedAt == null)
            val opening = FinancialTransaction(
                command.operationId, TransactionKind.OPENING, TransactionStatus.POSTED,
                command.financialTime, command.clientCreatedAt,
                listOf(AccountMovement(account.id, command.openingAmount)),
                // Sample uses no fabricated server timestamp; Clock is an injected deterministic input.
                createdAt = null,
            )
            // Validate aggregate Long arithmetic before acknowledging a write.
            accountBalance(account, CompleteLedger(state.value.events.values.toList() + opening))
            applied[command.operationId] = Applied.AccountCreated(command, account)
            state.value = state.value.copy(
                accounts = state.value.accounts + (account.id to account),
                events = state.value.events + (opening.id to opening), revision = state.value.revision + 1,
            )
            CommandResult.Accepted(account, WriteState.LOCAL_SAVED)
        } catch (error: IllegalArgumentException) { invalid(command.operationId, error) }
        catch (error: ArithmeticException) { invalid(command.operationId, error) }
    }

    override suspend fun recordExpense(command: RecordExpenseCommand): CommandResult<FinancialTransaction> = mutex.withLock {
        val previous = applied[command.operationId]
        if (previous != null) return@withLock if (previous is Applied.ExpenseRecorded && previous.command == command)
            CommandResult.Accepted(previous.value, WriteState.LOCAL_SAVED, true) else conflict(command.operationId)
        try {
            checkId(command.operationId)
            val account = requireNotNull(state.value.accounts[command.accountId]) { "Account not found" }
            val category = requireNotNull(state.value.categories[command.categoryId]) { "Category not found" }
            require(account.metadata.archivedAt == null && category.metadata.archivedAt == null) { "Archived reference cannot be selected" }
            require(command.amount.money.currency == account.asset) { "Expense currency must match account" }
            require(command.occurrenceId == null) { "Sample ordinary expense does not validate commitment links" }
            val native = NativeAmount.Fiat(command.amount.money)
            val lkr = if (account.asset == FiatCurrency.LKR) {
                require(command.historicalRate == null && command.rateSnapshotId == null)
                command.amount.money
            } else historicalLkrValue(native, requireNotNull(command.historicalRate) { "Historical LKR rate required" })
            val event = FinancialTransaction(
                command.operationId, TransactionKind.EXPENSE, TransactionStatus.POSTED,
                command.financialTime, command.clientCreatedAt,
                listOf(AccountMovement(account.id, NativeAmount.Fiat(-command.amount.money))),
                listOf(ReportComponent(ComponentNature.SPENDING, KnownLkrAmount.Known(lkr), category.id,
                    command.commitmentClass, originalAmount = native.takeIf { account.asset != FiatCurrency.LKR },
                    valuationRate = command.historicalRate, rateSnapshotId = command.rateSnapshotId)),
                valuations = if (account.asset == FiatCurrency.LKR) emptyList() else listOf(MovementValuation(
                    account.id, NativeAmount.Fiat(-command.amount.money), -lkr, requireNotNull(command.historicalRate),
                    command.financialTime.occurredAt, ValuationSource.MANUAL_ACTUAL, command.rateSnapshotId)),
                note = command.note, merchantLabel = command.merchantLabel,
            )
            accountBalance(account, CompleteLedger(state.value.events.values.toList() + event))
            applied[command.operationId] = Applied.ExpenseRecorded(command, event)
            state.value = state.value.copy(events = state.value.events + (event.id to event), revision = state.value.revision + 1)
            CommandResult.Accepted(event, WriteState.LOCAL_SAVED)
        } catch (error: IllegalArgumentException) { invalid(command.operationId, error) }
        catch (error: ArithmeticException) { invalid(command.operationId, error) }
    }

    override suspend fun saveCategory(operationId: String, category: Category): CommandResult<Category> = mutex.withLock {
        val previous = applied[operationId]
        if (previous != null) return@withLock if (previous is Applied.CategorySaved && previous.category == category)
            CommandResult.Accepted(previous.category, WriteState.LOCAL_SAVED, true) else conflict(operationId)
        try {
            checkId(operationId)
            val existing = state.value.categories[category.id]
            require(existing == null || category.metadata.revision == Math.addExact(existing.metadata.revision, 1)) { "Category revision conflict" }
            applied[operationId] = Applied.CategorySaved(category)
            state.value = state.value.copy(categories = state.value.categories + (category.id to category), revision = state.value.revision + 1)
            CommandResult.Accepted(category, WriteState.LOCAL_SAVED)
        } catch (error: IllegalArgumentException) { invalid(operationId, error) }
        catch (error: ArithmeticException) { invalid(operationId, error) }
    }

    override fun observeBudgets(): Flow<Observation<List<Budget>>> = state.map { data(it.budgets.values.sortedBy(Budget::id)) }

    override suspend fun saveBudget(operationId: String, budget: Budget): CommandResult<Budget> = mutex.withLock {
        val previous = applied[operationId]
        if (previous != null) return@withLock if (previous is Applied.BudgetSaved && previous.budget == budget)
            CommandResult.Accepted(previous.budget, WriteState.LOCAL_SAVED, true) else conflict(operationId)
        try {
            checkId(operationId)
            checkId(budget.id)
            require(budget.limit.money.currency == FiatCurrency.LKR)
            require(budget.limit.money.minor > 0L)
            require(!budget.rolloverEnabled)
            if (budget.scope == BudgetScope.CATEGORY_DISCRETIONARY) {
                require(!budget.categoryId.isNullOrBlank())
                checkId(budget.categoryId)
            } else {
                require(budget.categoryId == null)
            }
            applied[operationId] = Applied.BudgetSaved(budget)
            state.value = state.value.copy(budgets = state.value.budgets + (budget.id to budget), revision = state.value.revision + 1)
            CommandResult.Accepted(budget, WriteState.LOCAL_SAVED)
        } catch (error: IllegalArgumentException) { invalid(operationId, error) }
        catch (error: ArithmeticException) { invalid(operationId, error) }
    }

    suspend fun voidExpense(
        operationId: String,
        transactionId: String,
        voidReason: String,
        voidedAt: java.time.Instant = clock.instant(),
    ): CommandResult<FinancialTransaction> = mutex.withLock {
        val previous = applied[operationId]
        if (previous != null) {
            return@withLock if (previous is Applied.ExpenseVoided && previous.value.id == transactionId && previous.value.voidMetadata?.voidReason == voidReason.trim()) {
                CommandResult.Accepted(previous.value, WriteState.LOCAL_SAVED, true)
            } else {
                conflict(operationId)
            }
        }

        try {
            checkId(operationId)
            checkId(transactionId)
            require(voidReason.isNotBlank() && voidReason.length <= 1000) { "Enter a valid void reason." }

            val existingEvent = requireNotNull(state.value.events[transactionId]) { "Expense transaction not found" }
            System.err.println("DEBUG_VOID: existingEvent.id='${existingEvent.id}' operationId='${existingEvent.operationId}'")
            require(existingEvent.kind == TransactionKind.EXPENSE) { "Only expense transactions can be voided" }
            if (existingEvent.status == TransactionStatus.VOIDED) {
                require(existingEvent.voidMetadata?.voidReason == voidReason.trim()) { "This expense has already been voided" }
                return@withLock CommandResult.Accepted(existingEvent, WriteState.LOCAL_SAVED, true)
            }

            val voidMetadata = VoidMetadata(voidedAt = voidedAt, voidReason = voidReason.trim())
            val updatedEvent = FinancialTransaction(
                operationId = existingEvent.operationId,
                kind = existingEvent.kind,
                status = TransactionStatus.VOIDED,
                financialTime = existingEvent.financialTime,
                clientCreatedAt = existingEvent.clientCreatedAt,
                movements = existingEvent.movements,
                components = existingEvent.components,
                reservationEffects = existingEvent.reservationEffects,
                valuations = existingEvent.valuations,
                occurrenceId = existingEvent.occurrenceId,
                expectedPaymentId = existingEvent.expectedPaymentId,
                originalTransactionId = existingEvent.originalTransactionId,
                goalPlanId = existingEvent.goalPlanId,
                note = existingEvent.note,
                merchantLabel = existingEvent.merchantLabel,
                cryptoTrade = existingEvent.cryptoTrade,
                replacesTransactionId = existingEvent.replacesTransactionId,
                voidMetadata = voidMetadata,
                createdAt = existingEvent.createdAt,
                schemaVersion = existingEvent.schemaVersion,
                revision = existingEvent.revision,
            )

            applied[operationId] = Applied.ExpenseVoided(updatedEvent)
            state.value = state.value.copy(
                events = state.value.events + (transactionId to updatedEvent),
                revision = state.value.revision + 1,
            )

            CommandResult.Accepted(updatedEvent, WriteState.LOCAL_SAVED)
        } catch (error: IllegalArgumentException) {
            invalid(operationId, error)
        } catch (error: IllegalStateException) {
            invalid(operationId, error)
        } catch (error: ArithmeticException) {
            invalid(operationId, error)
        }
    }

    override suspend fun activityPage(query: ActivityQuery): Observation<ActivityPage> {
        val snapshot = state.value
        val cursorParts = query.cursor?.token?.split(':')
        val offset = if (cursorParts == null) 0 else {
            if (cursorParts.size != 3 || cursorParts[0].toLongOrNull() != snapshot.revision || cursorParts[1] != (query.accountId ?: "*"))
                return Observation.Failed(failure(FailureCode.CONFLICT, "Cursor scope/revision changed; restart pagination"))
            cursorParts[2].toIntOrNull()?.takeIf { it >= 0 }
                ?: return Observation.Failed(failure(FailureCode.VALIDATION, "Invalid cursor"))
        }
        val events = snapshot.events.values.filter { it.status == TransactionStatus.POSTED &&
            (query.accountId == null || query.accountId in it.accountIds) }
            .sortedWith(compareByDescending<FinancialTransaction> { it.financialTime.occurredAt }.thenByDescending { it.id })
        val page = events.drop(offset).take(query.pageSize)
        val next = (offset + page.size).takeIf { it < events.size }?.let { ActivityCursor("${snapshot.revision}:${query.accountId ?: "*"}:$it") }
        return Observation.Data(ActivityPage(page, next), metadata().copy(completeness = HistoryCompleteness.INCOMPLETE))
    }
}
