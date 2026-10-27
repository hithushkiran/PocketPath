package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseIncomeRepositoryFactory
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
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    private val incomeFactory: FirebaseIncomeRepositoryFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpectedPaymentsSessionState.signedOut())
    val state = _state.asStateFlow()

    private var generation = 0
    private var activeUserId: String? = null
    private var sessionScope: CoroutineScope? = null

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                val epoch = ++generation
                sessionScope?.cancel()
                sessionScope = null
                activeUserId = null

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
                        check(repository.userId == uid)
                        repository.observeExpectedPayments().collect { observation ->
                            if (epoch != generation) return@collect
                            ExpectedPaymentsSessionState.mapIfCurrent(
                                activeUserId = activeUserId,
                                expectedUserId = uid,
                                observation = observation,
                                start = LocalDate.MIN,
                                endInclusive = LocalDate.MAX,
                            )?.let { _state.value = it }
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
}
