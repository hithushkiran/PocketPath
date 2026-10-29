package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseIncomeRepositoryFactory
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.ExpectedPayment
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
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Session-scoped, read-only presentation of active expected payments.
 *
 * Creation and cancellation deliberately remain outside this increment. The full date range is
 * used so a future screen can add a user-selected planning window without hiding active records.
 */
@HiltViewModel
class ExpectedPaymentsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val foundationFactory: FirebaseFoundationFactory,
    private val incomeFactory: FirebaseIncomeRepositoryFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpectedPaymentsSessionState.signedOut())
    val state = _state.asStateFlow()

    private var generation = 0
    private var activeUserId: String? = null
    private var sessionScope: CoroutineScope? = null
    private var repository: com.pocketpath.domain.repository.IncomeRepository? = null
    private val cancellationOperations = mutableMapOf<String, String>()
    private val frozenReceipts = mutableMapOf<String, Pair<com.pocketpath.domain.model.FinancialTransaction, Long>>()

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                val epoch = ++generation
                sessionScope?.cancel()
                sessionScope = null
                activeUserId = null
                repository = null
                cancellationOperations.clear()
                frozenReceipts.clear()

                if (session !is AuthSession.SignedIn) {
                    _state.value = ExpectedPaymentsSessionState.signedOut()
                    return@collectLatest
                }

                val uid = session.user.uid
                activeUserId = uid
                _state.value = ExpectedPaymentsSessionState.loading(uid)

                try {
                    kotlinx.coroutines.coroutineScope {
                        sessionScope = this
                        val repository = incomeFactory.current()
                        val foundation = foundationFactory.current()
                        check(repository.userId == uid && foundation.userId == uid)
                        this@ExpectedPaymentsViewModel.repository = repository
                        launch {
                            foundation.observeAccounts().collect { observation ->
                                if (epoch != generation || activeUserId != uid) return@collect
                                val accounts = (observation as? Observation.Data)?.value ?: emptyList()
                                _state.update { it.copy(accounts = accounts) }
                            }
                        }
                        repository.observeExpectedPayments().collect { observation ->
                            if (epoch != generation) return@collect
                            ExpectedPaymentsSessionState.mapIfCurrent(
                                activeUserId = activeUserId,
                                expectedUserId = uid,
                                observation = observation,
                                start = LocalDate.MIN,
                                endInclusive = LocalDate.MAX,
                            )?.let { mapped ->
                                val current = _state.value
                                _state.value = mapped.copy(
                                    cancellingPaymentId = current.cancellingPaymentId,
                                    cancellationError = current.cancellationError,
                                    hasUnresolvedCancellationRetry = current.hasUnresolvedCancellationRetry,
                                    cancellationMessage = current.cancellationMessage,
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (epoch == generation && activeUserId == uid) {
                        _state.value = ExpectedPaymentsUiState(
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

    fun recordReceipt(expectedUserId: String, payment: ExpectedPayment, accountId: String?, amount: String) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId ||
            _state.value.payments.none { it.id == payment.id && it.metadata.revision == payment.metadata.revision }
        ) return
        val repo = repository ?: return
        val scope = sessionScope ?: return
        val frozen = frozenReceipts[payment.id] ?: try {
            ExpectedPaymentReceiptFactory.create(
                UUID.randomUUID().toString(), payment, accountId, amount, _state.value.accounts,
                Instant.now(), java.time.ZoneId.of("Asia/Colombo"),
            ) to payment.metadata.revision
        } catch (error: Exception) {
            _state.update { it.copy(cancellationError = RepositoryFailure(FailureCode.VALIDATION, error.message ?: "Invalid receipt.")) }
            return
        }
        frozenReceipts[payment.id] = frozen
        val epoch = generation
        _state.update { it.copy(cancellingPaymentId = payment.id, cancellationError = null, cancellationMessage = null) }
        scope.launch {
            try {
                when (val result = repo.recordExpectedPaymentReceipt(frozen.first, frozen.second)) {
                    is com.pocketpath.domain.repository.CommandResult.Accepted -> if (epoch == generation && activeUserId == expectedUserId) {
                        frozenReceipts.remove(payment.id)
                        _state.update { it.copy(cancellingPaymentId = null, cancellationMessage = if (result.replayed) "Receipt was already recorded." else "Receipt recorded.") }
                    }
                    is com.pocketpath.domain.repository.CommandResult.Rejected -> if (epoch == generation && activeUserId == expectedUserId) {
                        val uncertain = result.failure.code == FailureCode.NETWORK || result.failure.code == FailureCode.REJECTED
                        if (!uncertain) frozenReceipts.remove(payment.id)
                        _state.update { it.copy(cancellingPaymentId = null, cancellationError = result.failure, hasUnresolvedCancellationRetry = uncertain) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
        }
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
