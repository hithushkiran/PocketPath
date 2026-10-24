package com.pocketpath.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.data.firebase.UserCollection
import com.pocketpath.data.firebase.UserFirestoreScope
import com.pocketpath.domain.calculation.AccountBalance
import com.pocketpath.domain.calculation.accountBalance
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One authenticated session. Obtain through FirebaseFoundationFactory.
 * Do not bind this user-owned instance as an application singleton.
 *
 * This initial production adapter supports LKR OPENING and ordinary EXPENSE.
 * Writes currently require authoritative online transactions.
 */
class FirebaseFoundationRepository internal constructor(
    private val owned: UserFirestoreScope,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : AccountRepository, LedgerRepository {

    override val userId: String = owned.userId

    private val active = AtomicBoolean(true)
    private val closeActions = CopyOnWriteArraySet<() -> Unit>()

    private val accounts
        get() = owned.collection(UserCollection.ACCOUNTS)

    private val transactions
        get() = owned.collection(UserCollection.TRANSACTIONS)

    private val categories
        get() = owned.collection(UserCollection.CATEGORIES)

    internal fun closeSession() {
        if (active.compareAndSet(true, false)) {
            closeActions.forEach { it() }
            closeActions.clear()
        }
    }

    private fun isCurrentSession(): Boolean =
        active.get() && auth.currentUser?.uid == userId

    private fun requireSession() {
        if (!isCurrentSession()) {
            throw FoundationProblem(
                FailureCode.UNAUTHENTICATED,
                "Your session has ended. Sign in again.",
            )
        }
        owned.requireCurrentSession()
    }

    /**
     * Idempotent default-profile provisioning.
     * Existing preferences are validated and retained, never reset.
     */
    suspend fun ensureProfile(): CommandResult<UserProfile> = command(null) {
        val reference = owned.profile

        val result = firestore.runTransaction { transaction ->
            requireSession()
            val existing = transaction.get(reference)

            if (existing.exists()) {
                Applied(
                    FoundationCodec.profile(userId, requireNotNull(existing.data)),
                    replayed = true,
                )
            } else {
                transaction.set(reference, FoundationCodec.defaultProfileData())
                Applied(UserProfile(id = userId))
            }
        }.await()

        CommandResult.Accepted(
            value = result.value,
            writeState = WriteState.CLOUD_CONFIRMED,
            replayed = result.replayed,
        )
    }

    /**
     * Creates missing initial categories without overwriting existing choices.
     * Category editing/archival remains with the Spending feature.
     */
    suspend fun ensureBasicCategories(): CommandResult<Unit> = command(null) {
        val defaults = listOf(
            Category(
                id = "food",
                name = "Food",
                defaultCommitmentClass = CommitmentClass.DISCRETIONARY,
                sortOrder = 0,
            ),
            Category(
                id = "transport",
                name = "Transport",
                defaultCommitmentClass = CommitmentClass.DISCRETIONARY,
                sortOrder = 1,
            ),
            Category(
                id = "other",
                name = "Other",
                defaultCommitmentClass = CommitmentClass.DISCRETIONARY,
                sortOrder = 2,
            ),
        )

        val references = defaults.map { categories.document(it.id) }

        val replayed = firestore.runTransaction { transaction ->
            requireSession()

            // Read every document before writing any document.
            val existing = references.map { transaction.get(it) }

            existing.forEachIndexed { index, snapshot ->
                if (snapshot.exists()) {
                    FoundationCodec.category(
                        snapshot.id,
                        requireNotNull(snapshot.data),
                    )
                } else {
                    transaction.set(
                        references[index],
                        FoundationCodec.categoryData(defaults[index]),
                    )
                }
            }

            existing.all { it.exists() }
        }.await()

        CommandResult.Accepted(
            value = Unit,
            writeState = WriteState.CLOUD_CONFIRMED,
            replayed = replayed,
        )
    }

    fun observeCategories(): Flow<Observation<List<Category>>> =
        observeQuery(
            query = { categories.limit(5001) },
            completeHistory = false,
        ) { snapshot ->
            checkLimit(snapshot)
            snapshot.documents.map {
                FoundationCodec.category(it.id, requireNotNull(it.data))
            }.sortedWith(compareBy(Category::sortOrder, Category::id))
        }

    override fun observeAccounts(): Flow<Observation<List<Account>>> =
        observeQuery(
            query = { accounts.limit(5001) },
            completeHistory = false,
        ) { snapshot ->
            checkLimit(snapshot)
            snapshot.documents.map {
                FoundationCodec.account(it.id, requireNotNull(it.data))
            }.sortedBy { it.id }
        }

    override suspend fun findAccount(
        accountId: String,
    ): Observation<Account?> = read {
        FoundationCodec.checkId(accountId)
        val snapshot = accounts.document(accountId).get(Source.SERVER).await()
        requireSession()

        if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
            throw incomplete()
        }

        Observation.Data(
            value = if (snapshot.exists()) {
                FoundationCodec.account(snapshot.id, requireNotNull(snapshot.data))
            } else {
                null
            },
            metadata = serverMetadata(),
        )
    }

    override suspend fun createWithOpening(
        command: CreateAccountCommand,
    ): CommandResult<Account> = command(command.operationId) {
        FoundationCodec.checkId(command.operationId)
        val account = command.account

        require(account.openingTransactionId == command.operationId)
        require(account.openedOn == command.financialTime.occurredLocalDate)
        require(account.asset == FiatCurrency.LKR)
        require(command.openingAmount.asset == account.asset)

        val opening = FinancialTransaction(
            operationId = command.operationId,
            kind = TransactionKind.OPENING,
            status = TransactionStatus.POSTED,
            financialTime = FoundationCodec.storedTime(command.financialTime),
            clientCreatedAt = FoundationCodec.storedInstant(command.clientCreatedAt),
            movements = listOf(
                AccountMovement(account.id, command.openingAmount),
            ),
        )

        val accountData = FoundationCodec.accountData(account)
        val openingData = FoundationCodec.eventData(opening)

        val profileReference = owned.profile
        val accountReference = accounts.document(account.id)
        val openingReference = transactions.document(opening.id)

        val result = firestore.runTransaction { transaction ->
            requireSession()

            // All reads precede writes. This callback may be retried by Firestore.
            val profileSnapshot = transaction.get(profileReference)
            val accountSnapshot = transaction.get(accountReference)
            val openingSnapshot = transaction.get(openingReference)

            if (accountSnapshot.exists() || openingSnapshot.exists()) {
                val matches = accountSnapshot.exists() &&
                    openingSnapshot.exists() &&
                    FoundationCodec.sameAccount(
                        requireNotNull(accountSnapshot.data),
                        accountData,
                    ) &&
                    FoundationCodec.sameEvent(
                        requireNotNull(openingSnapshot.data),
                        openingData,
                    )

                if (!matches) throw conflict()

                FoundationCodec.event(
                    openingSnapshot.id,
                    requireNotNull(openingSnapshot.data),
                )

                Applied(
                    FoundationCodec.account(
                        accountSnapshot.id,
                        requireNotNull(accountSnapshot.data),
                    ),
                    replayed = true,
                )
            } else {
                if (profileSnapshot.exists()) {
                    FoundationCodec.profile(
                        userId,
                        requireNotNull(profileSnapshot.data),
                    )
                } else {
                    transaction.set(
                        profileReference,
                        FoundationCodec.defaultProfileData(),
                    )
                }

                transaction.set(accountReference, accountData)
                transaction.set(openingReference, openingData)

                Applied(account)
            }
        }.await()

        CommandResult.Accepted(
            value = result.value,
            writeState = WriteState.CLOUD_CONFIRMED,
            replayed = result.replayed,
        )
    }

    private fun effectiveQuery(): Query =
        transactions
            .whereEqualTo("status", TransactionStatus.POSTED.name)
            .limit(5001)

    override fun observeEffectiveLedger(): Flow<Observation<CompleteLedger>> =
        observeQuery(
            query = { effectiveQuery() },
            completeHistory = true,
        ) { snapshot ->
            decodeLedger(snapshot)
        }

    override fun observeAccountHistory(
        accountId: String,
    ): Flow<Observation<CompleteLedger>> {
        FoundationCodec.checkId(accountId)

        return observeEffectiveLedger().map { state ->
            when (state) {
                Observation.Loading -> Observation.Loading

                is Observation.Failed -> Observation.Failed(
                    failure = state.failure,
                )

                is Observation.Data -> Observation.Data(
                    value = CompleteLedger(
                        events = state.value.events.filter {
                            accountId in it.accountIds
                        },
                        scope = LedgerScope.AccountEvents(accountId),
                    ),
                    metadata = state.metadata,
                )
            }
        }
    }

    override fun observeBalance(
        accountId: String,
    ): Flow<Observation<AccountBalance>> {
        FoundationCodec.checkId(accountId)

        return combine(
            observeAccounts(),
            observeAccountHistory(accountId),
        ) { accountState, ledgerState ->
            balanceState(accountId, accountState, ledgerState)
        }
    }

    private fun balanceState(
        accountId: String,
        accountState: Observation<List<Account>>,
        ledgerState: Observation<CompleteLedger>,
    ): Observation<AccountBalance> {
        if (accountState is Observation.Failed) {
            return Observation.Failed(accountState.failure)
        }
        if (ledgerState is Observation.Failed) {
            return Observation.Failed(ledgerState.failure)
        }
        if (accountState !is Observation.Data ||
            ledgerState !is Observation.Data
        ) {
            return Observation.Loading
        }

        val account = accountState.value.firstOrNull { it.id == accountId }
            ?: return Observation.Failed(
                RepositoryFailure(FailureCode.NOT_FOUND, "Account not found."),
            )

        // Independent listeners may briefly receive different committed snapshots.
        // Do not publish zero before this account's opening event is observed.
        if (ledgerState.value.events.none { it.id == account.openingTransactionId }) {
            return Observation.Failed(
                RepositoryFailure(
                    FailureCode.INCOMPLETE_HISTORY,
                    "Waiting for the complete account history.",
                ),
            )
        }

        return try {
            Observation.Data(
                value = accountBalance(account, ledgerState.value),
                metadata = ledgerState.metadata,
            )
        } catch (exception: Exception) {
            Observation.Failed(exception.failure())
        }
    }

    override suspend fun activityPage(
        query: ActivityQuery,
    ): Observation<ActivityPage> = read {
        query.accountId?.let(FoundationCodec::checkId)

        val snapshot = effectiveQuery().get(Source.SERVER).await()
        requireSession()
        requireCompleteSnapshot(snapshot)

        val events = decodeLedger(snapshot).events
            .filter { query.accountId == null || query.accountId in it.accountIds }
            .sortedWith(
                compareByDescending<FinancialTransaction> {
                    it.financialTime.occurredAt
                }.thenByDescending { it.id },
            )

        // Cursor belongs to this user, filter and immutable event membership.
        val identity = buildString {
            append(userId)
            append('\n')
            append(query.accountId.orEmpty())
            events.forEach {
                append('\n')
                append(it.id)
            }
        }

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        val offset = query.cursor?.let { cursor ->
            val parts = cursor.token.split(':')
            if (parts.size != 2 || parts[0] != fingerprint) {
                throw FoundationProblem(
                    FailureCode.CONFLICT,
                    "Activity changed. Restart pagination.",
                )
            }

            parts[1].toIntOrNull()?.takeIf { it in 0..events.size }
                ?: throw FoundationProblem(
                    FailureCode.VALIDATION,
                    "Invalid Activity cursor.",
                )
        } ?: 0

        val page = events.drop(offset).take(query.pageSize)
        val nextOffset = offset + page.size
        val next = if (nextOffset < events.size) {
            ActivityCursor("$fingerprint:$nextOffset")
        } else {
            null
        }

        Observation.Data(
            value = ActivityPage(page, next),
            metadata = serverMetadata().copy(
                completeness = HistoryCompleteness.INCOMPLETE,
            ),
        )
    }

    override suspend fun recordExpense(
        command: RecordExpenseCommand,
    ): CommandResult<FinancialTransaction> = command(command.operationId) {
        FoundationCodec.checkId(command.operationId)
        FoundationCodec.checkId(command.accountId)
        FoundationCodec.checkId(command.categoryId)

        require(command.amount.money.currency == FiatCurrency.LKR)
        require(command.historicalRate == null && command.rateSnapshotId == null)
        require(command.occurrenceId == null) {
            "Commitment payments require the schedule implementation"
        }

        val event = FinancialTransaction(
            operationId = command.operationId,
            kind = TransactionKind.EXPENSE,
            status = TransactionStatus.POSTED,
            financialTime = FoundationCodec.storedTime(command.financialTime),
            clientCreatedAt = FoundationCodec.storedInstant(command.clientCreatedAt),
            movements = listOf(
                AccountMovement(
                    command.accountId,
                    NativeAmount.Fiat(-command.amount.money),
                ),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(command.amount.money),
                    categoryId = command.categoryId,
                    commitmentClass = command.commitmentClass,
                ),
            ),
            note = command.note,
            merchantLabel = command.merchantLabel,
        )

        val eventData = FoundationCodec.eventData(event)
        val eventReference = transactions.document(event.id)
        val accountReference = accounts.document(command.accountId)
        val categoryReference = categories.document(command.categoryId)

        val result = firestore.runTransaction { transaction ->
            requireSession()
            val existing = transaction.get(eventReference)

            if (existing.exists()) {
                if (!FoundationCodec.sameEvent(
                        requireNotNull(existing.data),
                        eventData,
                    )
                ) {
                    throw conflict()
                }

                Applied(
                    FoundationCodec.event(
                        existing.id,
                        requireNotNull(existing.data),
                    ),
                    replayed = true,
                )
            } else {
                val accountSnapshot = transaction.get(accountReference)
                val categorySnapshot = transaction.get(categoryReference)

                if (!accountSnapshot.exists() || !categorySnapshot.exists()) {
                    throw FoundationProblem(
                        FailureCode.NOT_FOUND,
                        "Select an existing account and category.",
                    )
                }

                val account = FoundationCodec.account(
                    accountSnapshot.id,
                    requireNotNull(accountSnapshot.data),
                )

                require(account.asset == FiatCurrency.LKR)
                require(account.metadata.archivedAt == null) {
                    "An archived account cannot receive a new expense"
                }
                require(categorySnapshot.get("archivedAt") == null) {
                    "An archived category cannot receive a new expense"
                }

                // Recorded expenses may make the balance negative.
                transaction.set(eventReference, eventData)
                Applied(event)
            }
        }.await()

        CommandResult.Accepted(
            value = result.value,
            writeState = WriteState.CLOUD_CONFIRMED,
            replayed = result.replayed,
        )
    }

    private fun decodeLedger(snapshot: QuerySnapshot): CompleteLedger {
        checkLimit(snapshot)

        return CompleteLedger(
            snapshot.documents.map {
                FoundationCodec.event(it.id, requireNotNull(it.data))
            },
        )
    }

    private fun checkLimit(snapshot: QuerySnapshot) {
        if (snapshot.size() > 5000) {
            throw FoundationProblem(
                FailureCode.INCOMPLETE_HISTORY,
                "History exceeds the supported complete-ledger limit.",
            )
        }
    }

    private fun requireCompleteSnapshot(snapshot: QuerySnapshot) {
        if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
            throw incomplete()
        }
        checkLimit(snapshot)
    }

    private fun <T> observeQuery(
        query: () -> Query,
        completeHistory: Boolean,
        decode: (QuerySnapshot) -> T,
    ): Flow<Observation<T>> = callbackFlow {
        trySend(Observation.Loading)

        var lastConfirmed: Observation.Data<T>? = null

        val sessionEnded: () -> Unit = {
            lastConfirmed = null
            trySend(
                Observation.Failed<T>(
                    RepositoryFailure(
                        FailureCode.UNAUTHENTICATED,
                        "Your session has ended. Sign in again.",
                    ),
                ),
            )
            channel.close()
            Unit
        }

        closeActions.add(sessionEnded)

        val registration = try {
            requireSession()

            query().addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!isCurrentSession()) {
                    sessionEnded()
                } else if (error != null) {
                    trySend(Observation.Failed(error.failure(), lastConfirmed))
                    channel.close()
                } else if (snapshot != null) {
                    try {
                        requireCompleteSnapshot(snapshot)

                        val data = Observation.Data(
                            value = decode(snapshot),
                            metadata = serverMetadata(completeHistory),
                        )

                        // Recheck after decoding before exposing user data.
                        requireSession()
                        lastConfirmed = data
                        trySend(data)
                    } catch (exception: Exception) {
                        if (!isCurrentSession()) {
                            sessionEnded()
                        } else {
                            trySend(
                                Observation.Failed(
                                    exception.failure(),
                                    lastConfirmed,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            trySend(Observation.Failed<T>(exception.failure()))
            channel.close()
            null
        }

        awaitClose {
            registration?.remove()
            closeActions.remove(sessionEnded)
            lastConfirmed = null
        }
    }

    private fun serverMetadata(history: Boolean = false) = ObservationMetadata(
        origin = DataOrigin.SERVER,
        completeness = if (history) {
            HistoryCompleteness.COMPLETE
        } else {
            HistoryCompleteness.NOT_APPLICABLE
        },
        writeState = WriteState.CLOUD_CONFIRMED,
        observedAt = Instant.now(),
    )

    private suspend fun <T> command(
        operationId: String?,
        block: suspend () -> CommandResult<T>,
    ): CommandResult<T> {
        return try {
            requireSession()
            val result = block()
            requireSession()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            CommandResult.Rejected(exception.failure(operationId))
        }
    }

    private suspend fun <T> read(
        block: suspend () -> Observation<T>,
    ): Observation<T> {
        return try {
            requireSession()
            val result = block()
            requireSession()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Observation.Failed(exception.failure())
        }
    }

    private fun Throwable.failure(operationId: String? = null): RepositoryFailure {
        if (!isCurrentSession()) {
            return RepositoryFailure(
                FailureCode.UNAUTHENTICATED,
                "Your session has ended. Sign in again.",
                operationId,
            )
        }

        val problem = generateSequence(this) { it.cause }
            .filterIsInstance<FoundationProblem>()
            .firstOrNull()

        if (problem != null) {
            return RepositoryFailure(problem.code, problem.message, operationId)
        }

        val firestoreError = generateSequence(this) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

        val code = when (firestoreError?.code) {
            FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                FailureCode.UNAUTHENTICATED

            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                FailureCode.PERMISSION_DENIED

            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                FailureCode.NETWORK

            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.ALREADY_EXISTS ->
                FailureCode.CONFLICT

            else -> if (this is IllegalArgumentException ||
                this is ArithmeticException
            ) {
                FailureCode.VALIDATION
            } else {
                FailureCode.REJECTED
            }
        }

        val message = when (code) {
            FailureCode.NETWORK -> "Connect to the internet and try again."
            FailureCode.UNAUTHENTICATED -> "Sign in again to continue."
            FailureCode.PERMISSION_DENIED -> "This action is not permitted."
            FailureCode.CONFLICT -> "The operation conflicts with existing data."
            FailureCode.VALIDATION -> "Check the financial details and try again."
            else -> "Unable to complete the financial operation."
        }

        return RepositoryFailure(code, message, operationId)
    }

    private fun conflict() = FoundationProblem(
        FailureCode.CONFLICT,
        "This identity already exists with different content.",
    )

    private fun incomplete() = FoundationProblem(
        FailureCode.INCOMPLETE_HISTORY,
        "Complete confirmed history is not currently available.",
    )

    private data class Applied<T>(
        val value: T,
        val replayed: Boolean = false,
    )

    private class FoundationProblem(
        val code: FailureCode,
        override val message: String,
    ) : RuntimeException(message)
}
