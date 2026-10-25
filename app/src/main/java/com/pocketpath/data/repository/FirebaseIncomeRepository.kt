package com.pocketpath.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.data.firebase.UserCollection
import com.pocketpath.data.firebase.UserFirestorePaths
import com.pocketpath.data.firebase.UserFirestoreScope
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.DataOrigin
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.HistoryCompleteness
import com.pocketpath.domain.repository.IncomeRepository
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.ObservationMetadata
import com.pocketpath.domain.repository.RepositoryFailure
import com.pocketpath.domain.repository.WriteState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One authenticated session's LKR-only income-source persistence.
 * Source IDs are caller-retained operation IDs, making a retry address the same immutable document.
 */
class FirebaseIncomeRepository internal constructor(
    private val owned: UserFirestoreScope,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : IncomeRepository {

    override val userId: String = owned.userId

    private val active = AtomicBoolean(true)
    private val closeActions = CopyOnWriteArraySet<() -> Unit>()

    private val sources
        get() = owned.collection(UserCollection.INCOME_SOURCES)

    private val accounts
        get() = owned.collection(UserCollection.ACCOUNTS)

    private val transactions
        get() = owned.collection(UserCollection.TRANSACTIONS)

    internal fun closeSession() {
        if (active.compareAndSet(true, false)) {
            closeActions.forEach { it() }
            closeActions.clear()
        }
    }

    private fun requireSession() {
        if (!active.get() || auth.currentUser?.uid != userId) {
            throw IncomeSourceProblem(
                FailureCode.UNAUTHENTICATED,
                "Your session has ended. Sign in again.",
                null,
            )
        }
        owned.requireCurrentSession()
    }

    override fun observeSources(): Flow<Observation<List<IncomeSource>>> = callbackFlow {
        trySend(Observation.Loading)
        try {
            requireSession()
        } catch (error: Exception) {
            trySend(Observation.Failed(error.failure()))
            close()
            return@callbackFlow
        }

        val registration = sources.limit(5001).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                trySend(Observation.Failed(error.failure()))
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener

            try {
                requireSession()
                if (snapshot.size() > 5000) {
                    trySend(Observation.Failed(RepositoryFailure(
                        FailureCode.INCOMPLETE_HISTORY,
                        "Too many income sources to load safely.",
                    )))
                } else {
                    trySend(Observation.Data(
                        snapshot.documents.map { document ->
                            FoundationCodec.incomeSource(
                                document.id,
                                requireNotNull(document.data),
                                document.metadata.hasPendingWrites(),
                            )
                        }.sortedBy(IncomeSource::id),
                        snapshot.metadata(),
                    ))
                }
            } catch (failure: Exception) {
                trySend(Observation.Failed(failure.failure()))
            }
        }
        val removeListener = { registration.remove() }
        closeActions.add(removeListener)
        awaitClose {
            removeListener()
            closeActions.remove(removeListener)
        }
    }

    override fun observeExpectedPayments(): Flow<Observation<List<com.pocketpath.domain.model.ExpectedPayment>>> =
        callbackFlow {
            trySend(Observation.Failed(RepositoryFailure(
                FailureCode.NOT_FOUND,
                "Expected payments are not implemented in this increment.",
            )))
            close()
        }

    override suspend fun saveExpectedPayment(
        operationId: String,
        payment: com.pocketpath.domain.model.ExpectedPayment,
    ): CommandResult<com.pocketpath.domain.model.ExpectedPayment> = CommandResult.Rejected(
        RepositoryFailure(
            FailureCode.REJECTED,
            "Expected payments are not implemented in this increment.",
            operationId,
        ),
    )

    override suspend fun saveSource(
        operationId: String,
        source: IncomeSource,
    ): CommandResult<IncomeSource> = try {
        requireSession()
        FoundationCodec.checkId(operationId)
        require(source.id == operationId) {
            "An income source ID must equal its retained operation ID."
        }
        require(source.defaultCurrency == null || source.defaultCurrency == FiatCurrency.LKR)
        val expected = FoundationCodec.incomeSourceData(source)
        val reference = sources.document(operationId)

        val result = firestore.runTransaction { transaction ->
            requireSession()
            val existing = transaction.get(reference)
            if (existing.exists()) {
                val stored = requireNotNull(existing.data)
                if (!FoundationCodec.sameIncomeSource(stored, expected)) {
                    throw IncomeSourceProblem(FailureCode.CONFLICT, "This operation ID was already used differently.", operationId)
                }
                FirebaseSourceApplied(FoundationCodec.incomeSource(operationId, stored), true)
            } else {
                transaction.set(reference, expected)
                FirebaseSourceApplied(source, false)
            }
        }.await()

        CommandResult.Accepted(result.source, WriteState.CLOUD_CONFIRMED, result.replayed)
    } catch (error: Exception) {
        CommandResult.Rejected(error.failure(operationId))
    }

    override suspend fun recordIncome(event: FinancialTransaction): CommandResult<FinancialTransaction> = try {
        requireSession()
        requireManualLkrIncome(event)
        val expected = FoundationCodec.eventData(event)
        val eventReference = transactions.document(event.id)
        val result = firestore.runTransaction { transaction ->
            requireSession()
            val existing = transaction.get(eventReference)
            if (existing.exists()) {
                val stored = requireNotNull(existing.data)
                if (!FoundationCodec.sameEvent(stored, expected)) throw IncomeSourceProblem(FailureCode.CONFLICT, "This operation ID was already used differently.", event.id)
                FirebaseIncomeApplied(FoundationCodec.event(event.id, stored), true)
            } else {
                val account = transaction.get(accounts.document(event.movements.single().accountId))
                if (!account.exists()) throw IncomeSourceProblem(FailureCode.NOT_FOUND, "Receiving account was not found.", event.id)
                val decodedAccount = FoundationCodec.account(account.id, requireNotNull(account.data))
                if (decodedAccount.asset != FiatCurrency.LKR || decodedAccount.metadata.archivedAt != null) {
                    throw IncomeSourceProblem(FailureCode.REJECTED, "Receiving account is not active LKR.", event.id)
                }
                val sourceId = requireNotNull(event.components.single().incomeSourceId)
                val source = transaction.get(sources.document(sourceId))
                if (!source.exists()) throw IncomeSourceProblem(FailureCode.NOT_FOUND, "Income source was not found.", event.id)
                val decodedSource = FoundationCodec.incomeSource(source.id, requireNotNull(source.data))
                if (decodedSource.metadata.archivedAt != null ||
                    (decodedSource.defaultCurrency != null && decodedSource.defaultCurrency != FiatCurrency.LKR)
                ) {
                    throw IncomeSourceProblem(FailureCode.REJECTED, "Income source is not eligible for LKR income.", event.id)
                }
                transaction.set(eventReference, expected)
                FirebaseIncomeApplied(event, false)
            }
        }.await()
        CommandResult.Accepted(result.event, WriteState.CLOUD_CONFIRMED, result.replayed)
    } catch (error: Exception) {
        CommandResult.Rejected(error.failure(event.id))
    }

    private fun requireManualLkrIncome(event: FinancialTransaction) {
        require(event.kind == TransactionKind.INCOME && event.status == TransactionStatus.POSTED)
        require(event.movements.size == 1 && event.components.size == 1)
        require(event.reservationEffects.isEmpty() && event.valuations.isEmpty())
        require(event.occurrenceId == null && event.expectedPaymentId == null && event.originalTransactionId == null && event.goalPlanId == null)
        require(event.replacesTransactionId == null && event.cryptoTrade == null && event.merchantLabel == null)
        val movement = event.movements.single().amount as? com.pocketpath.domain.money.NativeAmount.Fiat
            ?: error("Manual income requires a fiat movement.")
        require(movement.money.currency == FiatCurrency.LKR && movement.money.minor > 0)
        val component = event.components.single()
        require(component.incomeSourceId != null && component.categoryId == null && component.commitmentClass == null)
    }

    private fun QuerySnapshot.metadata(): ObservationMetadata = ObservationMetadata(
        origin = if (metadata.isFromCache) DataOrigin.CACHE else DataOrigin.SERVER,
        completeness = HistoryCompleteness.NOT_APPLICABLE,
        writeState = if (metadata.hasPendingWrites()) WriteState.PENDING else WriteState.CLOUD_CONFIRMED,
        observedAt = Instant.now(),
        pendingOperationIds = documents.filter { it.metadata.hasPendingWrites() }
            .mapTo(linkedSetOf()) { it.id },
    )

    private fun Exception.failure(operationId: String? = null): RepositoryFailure = when (this) {
        is IncomeSourceProblem -> RepositoryFailure(this.code, this.message, this.operationId)
        is FirebaseFirestoreException -> RepositoryFailure(
            when (this.code) {
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> FailureCode.UNAUTHENTICATED
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> FailureCode.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> FailureCode.NETWORK
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.ALREADY_EXISTS -> FailureCode.CONFLICT
                else -> FailureCode.REJECTED
            },
            message ?: "Income source write failed.",
            operationId,
        )
        is IllegalArgumentException, is IllegalStateException -> RepositoryFailure(
            FailureCode.VALIDATION,
            message ?: "Invalid income source.",
            operationId,
        )
        else -> RepositoryFailure(FailureCode.REJECTED, message ?: "Income source write failed.", operationId)
    }

    private data class FirebaseSourceApplied(val source: IncomeSource, val replayed: Boolean)
    private data class FirebaseIncomeApplied(val event: FinancialTransaction, val replayed: Boolean)
    private class IncomeSourceProblem(
        val code: FailureCode,
        override val message: String,
        val operationId: String?,
    ) : IllegalStateException(message)
}

/** Creates one income-source repository per authenticated Firebase session. */
@Singleton
class FirebaseIncomeRepositoryFactory @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val paths: UserFirestorePaths,
) {
    private val lock = Any()
    private var repository: FirebaseIncomeRepository? = null

    init {
        auth.addAuthStateListener { currentAuth ->
            synchronized(lock) {
                if (repository?.userId != currentAuth.currentUser?.uid) {
                    repository?.closeSession()
                    repository = null
                }
            }
        }
    }

    fun current(): FirebaseIncomeRepository = synchronized(lock) {
        val scope = paths.forCurrentUser()
        repository?.takeIf { it.userId == scope.userId } ?: FirebaseIncomeRepository(
            owned = scope,
            auth = auth,
            firestore = firestore,
        ).also { repository = it }
    }
}
