package com.pocketpath.domain.repository

import com.pocketpath.domain.calculation.AccountBalance
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class CreateAccountCommand(
    val operationId: String, val account: Account, val openingAmount: NativeAmount,
    val financialTime: FinancialTime, val clientCreatedAt: Instant,
)
data class RecordExpenseCommand(
    val operationId: String, val accountId: String, val amount: EntryAmount,
    val categoryId: String, val commitmentClass: CommitmentClass,
    val financialTime: FinancialTime, val clientCreatedAt: Instant,
    val historicalRate: ValuationRate? = null, val rateSnapshotId: String? = null,
    val occurrenceId: String? = null, val note: String? = null, val merchantLabel: String? = null,
)

interface AccountRepository : UserOwnedRepository {
    fun observeAccounts(): Flow<Observation<List<Account>>>
    suspend fun findAccount(accountId: String): Observation<Account?>
    /** Atomic account + exactly one OPENING. Same ID/same command replays; conflicting reuse fails. */
    suspend fun createWithOpening(command: CreateAccountCommand): CommandResult<Account>
}

/** Explicit complete history: never construct this from a visible Activity page or incomplete cache. */
sealed interface LedgerScope {
    data object AllEvents : LedgerScope
    data class AccountEvents(val accountId: String) : LedgerScope
}
class CompleteLedger(events: List<FinancialTransaction>, val scope: LedgerScope = LedgerScope.AllEvents) {
    val events: List<FinancialTransaction> = java.util.Collections.unmodifiableList(events.toList())
    init { require(events.map { it.operationId }.distinct().size == events.size) { "Duplicate ledger identity" } }
}

/** Opaque cursor includes a snapshot revision in the sample; adapters must preserve stable tie-breaking. */
data class ActivityCursor(val token: String)
data class ActivityQuery(val accountId: String? = null, val pageSize: Int = 50, val cursor: ActivityCursor? = null) {
    init { require(pageSize in 1..50) }
}
data class ActivityPage(val events: List<FinancialTransaction>, val nextCursor: ActivityCursor?)

interface LedgerRepository : UserOwnedRepository {
    /** Effective POSTED events, complete scope when Data. Failed/INCOMPLETE must not yield a full balance. */
    fun observeEffectiveLedger(): Flow<Observation<CompleteLedger>>
    /** Complete relevant effective event history, including events earlier than the visible period. */
    fun observeAccountHistory(accountId: String): Flow<Observation<CompleteLedger>>
    fun observeBalance(accountId: String): Flow<Observation<AccountBalance>>
    /** Display only: never a source for balances, Home summaries or reservations. */
    suspend fun activityPage(query: ActivityQuery): Observation<ActivityPage>
    /** Caller persists the operation ID with the draft. Real expenses may create a negative balance. */
    suspend fun recordExpense(command: RecordExpenseCommand): CommandResult<FinancialTransaction>
}

interface CategoryRepository : UserOwnedRepository {
    fun observeCategories(): Flow<Observation<List<Category>>>
    suspend fun saveCategory(operationId: String, category: Category): CommandResult<Category>
}

// Extension surfaces only. Config revisions/archival and references need same-user validation.
interface ProfileRepository : UserOwnedRepository {
    fun observeProfile(): Flow<Observation<UserProfile?>>
    suspend fun saveProfile(operationId: String, profile: UserProfile): CommandResult<UserProfile>
}
interface IncomeRepository : UserOwnedRepository {
    fun observeSources(): Flow<Observation<List<IncomeSource>>>
    fun observeExpectedPayments(): Flow<Observation<List<ExpectedPayment>>>
    suspend fun saveSource(operationId: String, source: IncomeSource): CommandResult<IncomeSource>
    suspend fun saveExpectedPayment(operationId: String, payment: ExpectedPayment): CommandResult<ExpectedPayment>
    suspend fun recordIncome(event: FinancialTransaction): CommandResult<FinancialTransaction>
}
interface ScheduleRepository : UserOwnedRepository {
    fun observeSchedules(): Flow<Observation<List<Schedule>>>
    fun observeOccurrences(): Flow<Observation<List<Occurrence>>>
    suspend fun saveSchedule(operationId: String, schedule: Schedule): CommandResult<Schedule>
    suspend fun saveOccurrence(operationId: String, occurrence: Occurrence): CommandResult<Occurrence>
    /** ONLINE: atomically read occurrence/revision, reject duplicate confirmation, post deterministic receipt. */
    suspend fun confirmIncome(occurrenceId: String, expectedRevision: Long, receipt: FinancialTransaction): CommandResult<FinancialTransaction>
    /** Commitment paid/outstanding state is derived from linked events, not a mutable payment counter. */
    suspend fun recordCommitmentPayment(command: RecordExpenseCommand): CommandResult<FinancialTransaction>
}
interface GoalRepository : UserOwnedRepository {
    fun observeGoals(): Flow<Observation<List<Goal>>>
    fun observePlans(): Flow<Observation<List<GoalPlan>>>
    suspend fun saveGoal(operationId: String, goal: Goal): CommandResult<Goal>
    suspend fun savePlan(operationId: String, plan: GoalPlan): CommandResult<GoalPlan>
    /** ONLINE: resolve available backing and existing allocations/releases before finalizing. */
    suspend fun changeReservation(event: FinancialTransaction): CommandResult<FinancialTransaction>
}
interface BudgetRepository : UserOwnedRepository {
    fun observeBudgets(): Flow<Observation<List<Budget>>>
    /** ONLINE: prevent overlapping active limits for the same scope. */
    suspend fun saveBudget(operationId: String, budget: Budget): CommandResult<Budget>
}
interface RateRepository : UserOwnedRepository {
    fun observeRates(): Flow<Observation<List<RateSnapshot>>>
    suspend fun saveRate(operationId: String, rate: RateSnapshot): CommandResult<RateSnapshot>
}
interface ProtectedLedgerRepository : UserOwnedRepository {
    /** ONLINE: verify original version/cumulative refund cap/relevant positions and atomically update links. */
    suspend fun recordRefund(event: FinancialTransaction, originalRevision: Long): CommandResult<FinancialTransaction>
    suspend fun replace(originalId: String, originalRevision: Long, replacement: FinancialTransaction, reason: String): CommandResult<FinancialTransaction>
    /** ONLINE: serialize cost-basis/quantity changes with position version; no offline finalization promise. */
    suspend fun recordCryptoTrade(event: FinancialTransaction, expectedPositionVersion: Long): CommandResult<FinancialTransaction>
    fun observeCryptoPositions(): Flow<Observation<List<CryptoPosition>>>
}
