package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseIncomeRepositoryFactory
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.RepositoryFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Authenticated expected-payment presentation with explicit, durable receipt recovery. */
@HiltViewModel
class ExpectedPaymentsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val foundationFactory: FirebaseFoundationFactory,
    private val incomeFactory: FirebaseIncomeRepositoryFactory,
    private val receiptStore: com.pocketpath.data.local.ReceiptRecoveryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpectedPaymentsSessionState.signedOut())
    val state = _state.asStateFlow()

    private var generation = 0
    private var activeUserId: String? = null
    private var sessionScope: CoroutineScope? = null
    private var receiptScope: CoroutineScope? = null
    private var repository: com.pocketpath.domain.repository.IncomeRepository? = null
    private var receiptCoordinator: ReceiptRecoveryCoordinator? = null
    private var receiptSignOut: ReceiptSignOutGate? = null
    private val cancellationOperations = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                val epoch = ++generation
                receiptScope?.cancel()
                receiptScope = null
                sessionScope?.cancel()
                sessionScope = null
                activeUserId = null
                repository = null
                receiptCoordinator = null
                receiptSignOut = null
                cancellationOperations.clear()

                if (session !is AuthSession.SignedIn) {
                    _state.value = ExpectedPaymentsSessionState.signedOut()
                    return@collectLatest
                }

                val uid = session.user.uid
                activeUserId = uid
                _state.value = ExpectedPaymentsSessionState.loading(uid)
                val receiptCoordinator = ReceiptRecoveryCoordinator(uid, receiptStore, {
                    epoch == generation && activeUserId == uid
                })
                this@ExpectedPaymentsViewModel.receiptCoordinator = receiptCoordinator
                // Receipt review/retry remains available if account/ledger observers fail.
                val receiptScope = CoroutineScope(viewModelScope.coroutineContext +
                    kotlinx.coroutines.SupervisorJob(viewModelScope.coroutineContext[kotlinx.coroutines.Job]))
                this@ExpectedPaymentsViewModel.receiptScope = receiptScope
                val signOut = ReceiptSignOutGate(
                    { epoch == generation && activeUserId == uid },
                    receiptCoordinator::flush,
                    { receiptCoordinator.state.value.error },
                )
                receiptSignOut = signOut
                receiptScope.launch {
                    signOut.state.collect { decision ->
                        if (epoch == generation && activeUserId == uid) _state.update { it.copy(receiptSignOut = decision) }
                    }
                }
                receiptScope.launch {
                    receiptCoordinator.state.collect { recovery ->
                        if (epoch == generation && activeUserId == uid) {
                            _state.update { it.copy(receiptRecovery = recovery) }
                        }
                    }
                }

                try {
                    receiptCoordinator.restore()
                    kotlinx.coroutines.coroutineScope {
                        sessionScope = this
                        val repository = incomeFactory.current()
                        check(repository.userId == uid)
                        this@ExpectedPaymentsViewModel.repository = repository
                        val foundation = foundationFactory.current()
                        check(foundation.userId == uid)
                        var paymentObservation: Observation<List<ExpectedPayment>> = Observation.Loading
                        var ledgerObservation: Observation<CompleteLedger> = Observation.Loading
                        fun updatePresentation() {
                            if (epoch != generation || activeUserId != uid) return
                            val mapped = ExpectedPaymentsSessionState.mapIfCurrent(
                                activeUserId = activeUserId,
                                expectedUserId = uid,
                                observation = paymentObservation,
                                ledger = ledgerObservation,
                                start = LocalDate.MIN,
                                endInclusive = LocalDate.MAX,
                            ) ?: return
                            _state.value = ExpectedPaymentsSessionState.preserveTransientState(mapped, _state.value)
                        }
                        launch {
                            foundation.observeAccounts().collect { observation ->
                                if (epoch != generation || activeUserId != uid) return@collect
                                _state.update { current ->
                                    ExpectedPaymentsSessionState.withAccounts(current, observation)
                                }
                            }
                        }
                        launch {
                            foundation.observeEffectiveLedger().collect { observation ->
                                if (epoch != generation || activeUserId != uid) return@collect
                                ledgerObservation = observation
                                updatePresentation()
                            }
                        }
                        repository.observeExpectedPayments().collect { observation ->
                            if (epoch != generation) return@collect
                            paymentObservation = observation
                            updatePresentation()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (epoch == generation && activeUserId == uid) {
                        _state.value = _state.value.copy(
                            userId = uid,
                            loading = false,
                            error = RepositoryFailure(
                                FailureCode.REJECTED,
                                error.message ?: "Unable to load expected payments.",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun onReceiptAction(expectedUserId: String, action: ReceiptAction) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        val coordinator = receiptCoordinator ?: return
        val scope = receiptScope ?: return
        scope.launch {
            when (action) {
                is ReceiptAction.Begin -> {
                    val snapshot = _state.value
                    val payment = snapshot.payments.firstOrNull { it.id == action.paymentId } ?: return@launch
                    if (snapshot.canRecordReceipts) coordinator.begin(payment, snapshot.selectedReceivingAccountId)
                }
                is ReceiptAction.Amount -> coordinator.edit(amount = action.text)
                ReceiptAction.Submit, ReceiptAction.Retry -> {
                    val snapshot = _state.value
                    val payment = snapshot.payments.firstOrNull { it.id == coordinator.state.value.receipt?.paymentId }
                    // Recovery does not depend on the current payment/account/ledger observations.
                    val fresh = !snapshot.loading && !snapshot.ledgerLoading && snapshot.ledgerError == null &&
                        snapshot.error == null && !snapshot.accountsLoading && snapshot.accountsError == null
                    coordinator.submit(payment, snapshot.accounts, fresh, repository, retry = action == ReceiptAction.Retry)
                }
                ReceiptAction.CancelDraft -> coordinator.cancelDraft()
                ReceiptAction.Acknowledge -> coordinator.acknowledge()
                ReceiptAction.Reload -> coordinator.restore()
                ReceiptAction.Review -> coordinator.review(repository)
            }
        }
    }

    fun selectReceivingAccount(expectedUserId: String, accountId: String?) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        _state.update { ExpectedPaymentsSessionState.selectReceivingAccount(it, accountId) }
        val coordinator = receiptCoordinator ?: return
        receiptScope?.launch { coordinator.edit(accountId = accountId, selectingAccount = true) }
    }

    fun flushReceiptAndContinue(expectedUserId: String, continuation: () -> Unit) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        val gate = receiptSignOut ?: return
        viewModelScope.launch { gate.request(continuation) }
    }

    fun retryReceiptSignOut(expectedUserId: String) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        val gate = receiptSignOut ?: return
        viewModelScope.launch { gate.retry() }
    }

    fun stayAfterReceiptStorageFailure(expectedUserId: String) {
        if (expectedUserId == activeUserId && expectedUserId == _state.value.userId) receiptSignOut?.stay()
    }

    fun signOutDespiteReceiptStorageFailure(expectedUserId: String) {
        if (expectedUserId == activeUserId && expectedUserId == _state.value.userId) receiptSignOut?.overrideStorageFailure()
    }

    fun cancel(expectedUserId: String, payment: ExpectedPayment) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId ||
            payment.state != com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE ||
            _state.value.payments.none {
                it.id == payment.id && it.metadata.revision == payment.metadata.revision &&
                    it.state == com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE
            }
        ) return
        val repo = repository ?: return
        val scope = sessionScope ?: return
        val epoch = generation
        val operationId = cancellationOperations.getOrPut(payment.id) { UUID.randomUUID().toString() }
        _state.update {
            it.copy(
                cancellingPaymentId = payment.id,
                cancellationError = null,
                cancellationMessage = null,
            )
        }
        scope.launch {
            try {
                when (val result = repo.cancelExpectedPayment(operationId, payment.id, payment.metadata.revision)) {
                    is com.pocketpath.domain.repository.CommandResult.Accepted -> if (epoch == generation && activeUserId == expectedUserId) {
                        cancellationOperations.remove(payment.id)
                        _state.update {
                            it.copy(
                                cancellingPaymentId = null,
                                cancellationError = null,
                                hasUnresolvedCancellationRetry = false,
                                cancellationMessage = if (result.replayed) {
                                    "Expected payment was already cancelled."
                                } else {
                                    "Expected payment cancelled."
                                },
                            )
                        }
                    }
                    is com.pocketpath.domain.repository.CommandResult.Rejected -> if (epoch == generation && activeUserId == expectedUserId) {
                        val uncertain = result.failure.code == FailureCode.NETWORK || result.failure.code == FailureCode.REJECTED
                        if (!uncertain) cancellationOperations.remove(payment.id)
                        _state.update {
                            it.copy(
                                cancellingPaymentId = null,
                                cancellationError = result.failure,
                                hasUnresolvedCancellationRetry = uncertain,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == generation && activeUserId == expectedUserId) {
                    _state.update {
                        it.copy(
                            cancellingPaymentId = null,
                            cancellationError = RepositoryFailure(
                                FailureCode.REJECTED,
                                error.message ?: "Unable to cancel expected payment.",
                                operationId,
                            ),
                            hasUnresolvedCancellationRetry = true,
                        )
                    }
                }
            }
        }
    }
}
