package com.pocketpath.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.data.firebase.UserCollection
import com.pocketpath.data.firebase.UserFirestoreScope
import com.pocketpath.data.local.*
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineExpenseWriter @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val local: FinanceLocalStore,
) {
    private val submitMutex = Mutex()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun current(uid: String) = auth.currentUser?.uid == uid

    private fun requireCurrent(uid: String) {
        check(current(uid)) { "Session ended" }
    }

    private fun failure(
        code: FailureCode,
        message: String,
        id: String,
    ) = CommandResult.Rejected(RepositoryFailure(code, message, id))

    private suspend fun cached(reference: DocumentReference): DocumentSnapshot? {
        return try {
            reference.get(Source.CACHE).await().takeIf { it.exists() }
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                null
            } else {
                throw error
            }
        }
    }

    suspend fun submit(
        owned: UserFirestoreScope,
        command: RecordExpenseCommand,
    ): CommandResult<FinancialTransaction> = submitMutex.withLock {
        val uid = owned.userId
        val id = command.operationId

        try {
            requireCurrent(uid)
            FoundationCodec.checkId(id)
            FoundationCodec.checkId(command.accountId)
            FoundationCodec.checkId(command.categoryId)

            val posting = StoredExpense.from(command)
            val event = posting.event()
            val expected = FoundationCodec.eventData(event)
            val reference = owned.collection(UserCollection.TRANSACTIONS).document(id)

            val previous = local.read(uid).receipts.firstOrNull {
                it.posting.operationId == id
            }

            if (previous != null && previous.posting != posting) {
                return@withLock failure(
                    FailureCode.CONFLICT,
                    "This save identity has different content.",
                    id,
                )
            }

            if (previous?.phase == ReceiptPhase.CONFIRMED) {
                return@withLock CommandResult.Accepted(
                    event,
                    WriteState.CLOUD_CONFIRMED,
                    replayed = true,
                )
            }

            val existing = cached(reference)
            requireCurrent(uid)

            if (existing != null) {
                if (!FoundationCodec.sameEvent(
                        requireNotNull(existing.data),
                        expected,
                    )
                ) {
                    return@withLock failure(
                        FailureCode.CONFLICT,
                        "This save identity belongs to a different entry.",
                        id,
                    )
                }

                val receipt = previous ?: local.beginReceipt(uid, posting)

                if (existing.metadata.hasPendingWrites()) {
                    local.markReceipt(
                        uid, id, receipt.attempt, ReceiptPhase.PENDING,
                    )
                    return@withLock CommandResult.Accepted(
                        event,
                        WriteState.PENDING,
                        replayed = true,
                    )
                }

                val confirmed = FoundationCodec.event(
                    existing.id,
                    requireNotNull(existing.data),
                )

                local.markReceipt(
                    uid, id, receipt.attempt, ReceiptPhase.CONFIRMED,
                )

                return@withLock CommandResult.Accepted(
                    confirmed,
                    WriteState.CLOUD_CONFIRMED,
                    replayed = true,
                )
            }

            // Do not resend an uncertain request after restart.
            // Firestore may already own its persisted write.
            if (previous?.phase == ReceiptPhase.PREPARED ||
                previous?.phase == ReceiptPhase.PENDING
            ) {
                return@withLock failure(
                    FailureCode.NETWORK,
                    "This save needs confirmation. Reconnect and retry the check.",
                    id,
                )
            }

            val accountSnapshot = cached(
                owned.collection(UserCollection.ACCOUNTS).document(command.accountId),
            )
            val categorySnapshot = cached(
                owned.collection(UserCollection.CATEGORIES).document(command.categoryId),
            )
            requireCurrent(uid)

            if (accountSnapshot == null || categorySnapshot == null) {
                return@withLock failure(
                    FailureCode.NOT_FOUND,
                    "Load the account and category online before using them offline.",
                    id,
                )
            }

            val account = FoundationCodec.account(
                accountSnapshot.id,
                requireNotNull(accountSnapshot.data),
            )
            val category = FoundationCodec.category(
                categorySnapshot.id,
                requireNotNull(categorySnapshot.data),
            )

            require(account.asset == FiatCurrency.LKR)
            require(account.metadata.archivedAt == null)
            require(category.metadata.archivedAt == null)

            // This durable receipt is recorded before handing the write to Firestore.
            val receipt = local.beginReceipt(uid, posting)
            requireCurrent(uid)

            val accepted = CompletableDeferred<CommandResult<FinancialTransaction>>()

            val registration = reference.addSnapshotListener(
                MetadataChanges.INCLUDE,
            ) { snapshot, _ ->
                if (snapshot != null &&
                    snapshot.exists() &&
                    snapshot.metadata.hasPendingWrites() &&
                    FoundationCodec.sameEvent(
                        requireNotNull(snapshot.data),
                        expected,
                    )
                ) {
                    worker.launch {
                        try {
                            local.markReceipt(
                                uid, id, receipt.attempt, ReceiptPhase.PENDING,
                            )
                            accepted.complete(
                                CommandResult.Accepted(event, WriteState.PENDING),
                            )
                        } catch (_: Exception) {
                            accepted.complete(
                                failure(
                                    FailureCode.NETWORK,
                                    "Save result is not confirmed. Keep this entry and retry.",
                                    id,
                                ),
                            )
                        }
                    }
                }
            }

            try {
                reference.set(expected).addOnCompleteListener { task ->
                    worker.launch {
                        try {
                            if (task.isSuccessful) {
                                local.markReceipt(
                                    uid, id, receipt.attempt, ReceiptPhase.CONFIRMED,
                                )
                                accepted.complete(
                                    CommandResult.Accepted(
                                        event,
                                        WriteState.CLOUD_CONFIRMED,
                                    ),
                                )
                            } else {
                                // A rejected duplicate might already exist on the server.
                                // Read it before deciding that the logical save failed.
                                reconcileOne(uid, receipt)

                                val resolved = local.read(uid).receipts.firstOrNull {
                                    it.posting.operationId == id
                                }

                                if (resolved?.phase == ReceiptPhase.CONFIRMED) {
                                    accepted.complete(
                                        CommandResult.Accepted(
                                            event,
                                            WriteState.CLOUD_CONFIRMED,
                                            replayed = true,
                                        ),
                                    )
                                } else {
                                    accepted.complete(
                                        failure(
                                            FailureCode.NETWORK,
                                            resolved?.message
                                                ?: "Save result is not confirmed. Reconnect to check.",
                                            id,
                                        ),
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            accepted.complete(
                                failure(
                                    FailureCode.NETWORK,
                                    "Save result is not confirmed. Reconnect to check.",
                                    id,
                                ),
                            )
                        }
                    }
                }

                accepted.await()
            } finally {
                registration.remove()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(
                if (current(uid)) FailureCode.VALIDATION
                else FailureCode.UNAUTHENTICATED,
                if (current(uid)) {
                    "Check the expense details and available account/category."
                } else {
                    "Sign in again to continue."
                },
                id,
            )
        }
    }

    /**
     * Read-only recovery. Never submits a write from a stored receipt.
     */
    suspend fun reconcile(uid: String) {
        requireCurrent(uid)

        val unresolved = submitMutex.withLock {
            requireCurrent(uid)
            local.read(uid).receipts.filter {
                it.phase == ReceiptPhase.PREPARED ||
                    it.phase == ReceiptPhase.PENDING
            }
        }
        if (unresolved.isEmpty()) return

        firestore.waitForPendingWrites().await()
        requireCurrent(uid)

        unresolved.forEach {
            requireCurrent(uid)
            reconcileOne(uid, it)
        }
    }

    private suspend fun reconcileOne(uid: String, receipt: ExpenseReceipt) {
        requireCurrent(uid)

        val id = receipt.posting.operationId
        val reference = firestore.collection("users").document(uid)
            .collection("transactions").document(id)

        val snapshot = reference.get(Source.SERVER).await()
        requireCurrent(uid)

        if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
            return
        }

        if (snapshot.exists()) {
            val matches = FoundationCodec.sameEvent(
                requireNotNull(snapshot.data),
                FoundationCodec.eventData(receipt.posting.event()),
            )

            if (matches) {
                FoundationCodec.event(snapshot.id, requireNotNull(snapshot.data))
                local.markReceipt(
                    uid, id, receipt.attempt, ReceiptPhase.CONFIRMED,
                )
            } else {
                local.markReceipt(
                    uid, id, receipt.attempt, ReceiptPhase.REJECTED,
                    "This save identity belongs to a different entry. Review the draft.",
                )
            }
        } else {
            local.markReceipt(
                uid, id, receipt.attempt, ReceiptPhase.REJECTED,
                "The expense was not synchronized. Review and retry.",
            )
        }
    }
}
