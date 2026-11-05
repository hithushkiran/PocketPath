package com.pocketpath.feature.income

import com.pocketpath.data.local.*
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.domain.model.*
import com.pocketpath.domain.repository.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReceiptRecoveryState(
    val restoring: Boolean = true,
    val receipt: StoredReceipt? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val storageBlocked: Boolean = false,
    val reviewedOperation: String? = null,
    val reviewedPayment: String? = null,
) {
    val hasWork: Boolean get() = restoring || receipt != null || storageBlocked
}

sealed interface ReceiptAction {
    data class Begin(val paymentId: String) : ReceiptAction
    data class Amount(val text: String) : ReceiptAction
    data object Submit : ReceiptAction
    data object Retry : ReceiptAction
    data object CancelDraft : ReceiptAction
    data object Acknowledge : ReceiptAction
    data object Reload : ReceiptAction
    data object Review : ReceiptAction
}

/** One immutable owner/session. Only an explicit submit/retry calls the repository. */
class ReceiptRecoveryCoordinator(
    val owner: String,
    private val store: ReceiptRecoveryStore,
    private val isCurrent: () -> Boolean,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Colombo")),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ReceiptRecoveryState())
    val state = _state.asStateFlow()
    private var persisted: StoredReceipt? = null
    private var loaded = false

    suspend fun restore() = mutex.withLock {
        if (!isCurrent() || _state.value.busy) return@withLock
        try {
            val disk = store.read(owner)
            // Do not discard an in-memory frozen command after an ambiguous storage failure.
            val retained = _state.value.receipt
            check(retained?.command == null || disk == persisted || disk?.command == retained.command) {
                "Stored recovery differs from the retained command. Review is required."
            }
            persisted = disk
            loaded = true
            _state.value = ReceiptRecoveryState(restoring = false, receipt =
                if (retained?.command != null && disk?.command == null) retained else disk)
        } catch (error: Exception) {
            _state.value = _state.value.copy(restoring = false, storageBlocked = true, error = "Recovery storage: ${error.message}")
        }
    }

    private suspend fun save(next: StoredReceipt?): Boolean {
        val previous = _state.value.receipt
        _state.value = _state.value.copy(receipt = next)
        return try {
            store.replace(owner, persisted, next)
            persisted = next
            _state.value = _state.value.copy(storageBlocked = false, error = null)
            true
        } catch (error: Exception) {
            _state.value = _state.value.copy(receipt = next ?: previous, storageBlocked = true, error = "Recovery storage: ${error.message}")
            false
        }
    }

    suspend fun begin(payment: ExpectedPayment, accountId: String?) = mutex.withLock {
        if (!isCurrent() || !loaded || _state.value.hasWork || _state.value.busy) return@withLock
        save(StoredReceipt(payment.id, payment.projectLabel, accountId))
    }

    suspend fun edit(amount: String? = null, accountId: String? = null, selectingAccount: Boolean = false) = mutex.withLock {
        val record = _state.value.receipt ?: return@withLock
        if (!isCurrent() || record.phase != ReceiptRecoveryPhase.DRAFT || _state.value.busy || _state.value.storageBlocked) return@withLock
        save(record.copy(amountText = amount?.take(128) ?: record.amountText,
            accountId = if (selectingAccount) accountId else record.accountId))
    }

    suspend fun cancelDraft() = mutex.withLock {
        if (!isCurrent() || !loaded || _state.value.busy || _state.value.storageBlocked) return@withLock
        if (_state.value.receipt?.phase == ReceiptRecoveryPhase.DRAFT) save(null)
    }

    suspend fun acknowledge() = mutex.withLock {
        if (!isCurrent() || _state.value.busy || _state.value.storageBlocked) return@withLock
        if (persisted?.phase in setOf(ReceiptRecoveryPhase.CONFIRMED, ReceiptRecoveryPhase.RESOLVED) && persisted == _state.value.receipt) save(null)
    }

    /** All edits are write-through. Already durable (including empty) needs no extra write. */
    suspend fun flush(): Boolean = mutex.withLock {
        if (!isCurrent() || !loaded || _state.value.restoring) return@withLock false
        if (!_state.value.storageBlocked && persisted == _state.value.receipt) return@withLock true
        save(_state.value.receipt)
    }

    /** Review never submits. It shares only the snapshot handoff with submission, not network waits. */
    suspend fun review(repository: IncomeRepository?) {
        val frozen = mutex.withLock {
            if (!isCurrent() || !loaded || _state.value.restoring || _state.value.busy) return
            val record = _state.value.receipt ?: return
            if (record.phase == ReceiptRecoveryPhase.CONFIRMED || record.phase == ReceiptRecoveryPhase.RESOLVED) {
                if (_state.value.storageBlocked) save(record)
                return
            }
            if (record.phase != ReceiptRecoveryPhase.PREPARED) return
            if (repository == null || repository.userId != owner) {
                _state.value = _state.value.copy(error = "The owning user's repository is unavailable for online review.")
                return
            }
            if (!save(record)) return
            _state.value = _state.value.copy(busy = true)
            requireNotNull(record.command)
        }
        try {
            if (!isCurrent()) return
            val result = requireNotNull(repository).inspectExpectedPaymentReceipt(frozen.operationId, frozen.paymentId)
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!isCurrent()) return@withLock // A stale inspection cannot resolve another session's work.
                    when (result) {
                        is ReceiptInspection.Unavailable -> _state.value = _state.value.copy(
                            error = "Review unavailable: ${result.failure.code}: ${result.failure.message}. The retained command remains unresolved.",
                        )
                        is ReceiptInspection.Authoritative -> {
                            require(result.owner == owner && result.operationId == frozen.operationId && result.paymentId == frozen.paymentId)
                            require(result.operation == null || result.operation.id == frozen.operationId)
                            require(result.payment == null || result.payment.id == frozen.paymentId)
                            val operation = result.operation?.let(::operationReviewText)
                            val payment = result.payment?.toString()
                            _state.value = _state.value.copy(reviewedOperation = operation ?: "Authoritatively absent", reviewedPayment = payment ?: "Payment absent")
                            val retained = requireNotNull(_state.value.receipt)
                            val resolution = resolveInspection(frozen, result, operation, payment)
                            save(retained.copy(
                                phase = if (resolution.kind == ReceiptResolutionKind.IDENTICAL) ReceiptRecoveryPhase.CONFIRMED else ReceiptRecoveryPhase.RESOLVED,
                                replayed = resolution.kind == ReceiptResolutionKind.IDENTICAL,
                                resolution = resolution,
                            ))
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(error = "Review interrupted. No resolution was established; the original command is retained.")
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = "Resolution unavailable: ${error.message}. The original command is retained.")
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun resolveInspection(
        command: StoredReceiptCommand,
        snapshot: ReceiptInspection.Authoritative,
        operationText: String?,
        paymentText: String?,
    ): StoredReceiptResolution {
        val operation = snapshot.operation
        val payment = snapshot.payment
        val kind: ReceiptResolutionKind
        val reason: String
        if (operation != null) {
            if (FoundationCodec.sameExpectedPaymentReceipt(FoundationCodec.eventData(operation), FoundationCodec.eventData(command.event()))) {
                // Exactly the same consistency checks as the repository's existing-event replay branch.
                requireNotNull(payment) { "Identical operation has no corresponding payment." }
                require(payment.incomeSourceId == command.sourceId && payment.amountDue.money.currency == com.pocketpath.domain.money.FiatCurrency.LKR &&
                    payment.receivedAmountMinor >= command.amountMinor) { "Identical operation/payment state is inconsistent." }
                kind = ReceiptResolutionKind.IDENTICAL
                reason = "The server operation is canonically identical and its payment state is consistent. Recovered confirmation without a new financial write."
            } else {
                kind = ReceiptResolutionKind.DIFFERENT_OPERATION
                reason = "A different immutable server operation occupies this ID. The retained command did not become that operation; this is a resolved conflict, not confirmation of our receipt."
            }
        } else {
            requireNotNull(payment) { "Absence without a current payment does not prove permanent rejection." }
            require(payment.incomeSourceId == command.sourceId && payment.amountDue.money.currency == com.pocketpath.domain.money.FiatCurrency.LKR) {
                "Payment references differ from the retained command."
            }
            require(payment.metadata.revision > command.originalRevision ||
                payment.state == ExpectedPaymentState.CANCELLED && payment.metadata.revision >= command.originalRevision) {
                "Absence alone is insufficient: the original revision is still eligible to commit."
            }
            // recordExpectedPaymentReceipt re-reads the payment in its write transaction and enforces
            // originalRevision/ACTIVE through allocateExpectedPaymentReceipt. Current rules permit
            // only revision+1 receipt/cancellation transitions, no reactivation, deletion or rollback.
            // An earlier cancelled await's transaction must conflict/re-read this changed payment;
            // it cannot later commit the original command, even if its async task is still running.
            kind = ReceiptResolutionKind.PERMANENT_REJECTION
            reason = "Operation authoritatively absent; payment is ${payment.state} at revision ${payment.metadata.revision}. " +
                "Monotonic revision/terminal cancellation rules and the repository's revision-checked transaction permanently prevent original revision ${command.originalRevision} from committing. This is not a receipt confirmation."
        }
        return StoredReceiptResolution(kind, FoundationCodec.storedInstant(Instant.now(clock)).toString(), reason, operationText, paymentText)
    }

    private fun operationReviewText(event: FinancialTransaction): String = buildString {
        append("ID: ${event.id}; ${event.kind}; ${event.status}; time: ${event.financialTime}; client: ${event.clientCreatedAt}; created: ${event.createdAt}")
        append("\nMovements: ${event.movements}\nComponents: ${event.components}\nReservations: ${event.reservationEffects}\nValuations: ${event.valuations}")
        append("\nLinks: payment=${event.expectedPaymentId}; occurrence=${event.occurrenceId}; original=${event.originalTransactionId}; goalPlan=${event.goalPlanId}; replacement=${event.replacesTransactionId}")
        append("\nNote: ${event.note}; merchant: ${event.merchantLabel}; trade: ${event.cryptoTrade}; void: ${event.voidMetadata}; schema: ${event.schemaVersion}; revision: ${event.revision}")
    }

    suspend fun submit(
        payment: ExpectedPayment?,
        accounts: List<Account>,
        freshInputsAvailable: Boolean,
        repository: IncomeRepository?,
        retry: Boolean = false,
    ) {
        val frozen = mutex.withLock {
            if (!isCurrent() || !loaded || _state.value.restoring || _state.value.busy) return
            val record = _state.value.receipt ?: return
            if (record.phase == ReceiptRecoveryPhase.CONFIRMED || record.phase == ReceiptRecoveryPhase.RESOLVED) {
                if (retry && _state.value.storageBlocked) save(record)
                return
            }
            if (repository == null || repository.userId != owner) {
                _state.value = _state.value.copy(error = "The owning user's repository is unavailable. Retry later.")
                return
            }
            val prepared = if (record.phase == ReceiptRecoveryPhase.DRAFT) {
                if (retry || _state.value.storageBlocked) return
                try {
                    require(freshInputsAvailable) { "Complete payment, account and ledger inputs are required for a new receipt." }
                    val current = requireNotNull(payment) { "Expected payment is unavailable." }
                    require(current.id == record.paymentId)
                    val event = ExpectedPaymentReceiptFactory.create(
                        StableIds.expectedPaymentReceipt(current.id, current.metadata.revision), current,
                        record.accountId, record.amountText, accounts, Instant.now(clock), ZoneId.of("Asia/Colombo"),
                    )
                    record.copy(phase = ReceiptRecoveryPhase.PREPARED, command = StoredReceiptCommand.from(event, current.metadata.revision))
                } catch (error: Exception) {
                    _state.value = _state.value.copy(error = error.message ?: "Invalid receipt.")
                    return
                }
            } else {
                if (!retry) return // Repeated Save taps are not retries.
                record
            }
            // Persist before ANY repository submission, including retries after a storage error.
            if (!save(prepared)) return
            _state.value = _state.value.copy(busy = true)
            requireNotNull(prepared.command)
        }
        // No coordinator/store mutex is held during a possibly indefinite network wait.
        try {
            if (!isCurrent()) return
            val result = requireNotNull(repository).recordExpectedPaymentReceipt(frozen.event(), frozen.originalRevision)
            withContext(NonCancellable) {
                mutex.withLock {
                    when (result) {
                        is CommandResult.Accepted -> {
                            if (result.writeState != WriteState.CLOUD_CONFIRMED || !FoundationCodec.sameExpectedPaymentReceipt(
                                    FoundationCodec.eventData(result.value), FoundationCodec.eventData(frozen.event()))) {
                                _state.value = _state.value.copy(error = "Receipt result differs or is not cloud confirmed. Retain this operation for review.")
                            } else {
                                val record = requireNotNull(_state.value.receipt)
                                save(record.copy(phase = ReceiptRecoveryPhase.CONFIRMED, replayed = result.replayed))
                            }
                        }
                        is CommandResult.Rejected -> _state.value = _state.value.copy(
                            error = "${result.failure.code}: ${result.failure.message} Retained operation ${frozen.operationId}; review or retry this same command.",
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(error = "Submission interrupted; its outcome is unknown. Retry the retained operation explicitly.")
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = "Outcome unknown: ${error.message}. Retry the retained operation explicitly.")
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }
}
