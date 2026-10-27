package com.pocketpath.feature.income

import com.pocketpath.domain.calculation.activeExpectedPaymentTotalsByCurrency
import com.pocketpath.domain.calculation.activeExpectedPaymentsDueIn
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.ObservationMetadata
import com.pocketpath.domain.repository.RepositoryFailure
import java.time.LocalDate

/**
 * Read-only presentation state for a caller-selected planning-date window.
 *
 * A session-scoped ViewModel supplies repository observations. This state remains read-only:
 * expected-payment creation and cancellation are not part of this presentation surface.
 */
data class ExpectedPaymentsUiState(
    /** The signed-in owner of this presentation snapshot; never render another user's data. */
    val userId: String? = null,
    val loading: Boolean = true,
    val payments: List<ExpectedPayment> = emptyList(),
    val totalsByCurrency: Map<FiatCurrency, Money> = emptyMap(),
    val error: RepositoryFailure? = null,
    val metadata: ObservationMetadata? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && payments.isEmpty()
    val isPopulated: Boolean get() = !loading && payments.isNotEmpty()
}

/**
 * Small session boundary kept outside the ViewModel so its cross-user behaviour is JVM-testable.
 */
internal object ExpectedPaymentsSessionState {
    fun signedOut() = ExpectedPaymentsUiState(loading = false)

    fun loading(userId: String) = ExpectedPaymentsUiState(userId = userId)

    fun mapIfCurrent(
        activeUserId: String?,
        expectedUserId: String,
        observation: Observation<List<ExpectedPayment>>,
        start: LocalDate,
        endInclusive: LocalDate,
    ): ExpectedPaymentsUiState? {
        if (activeUserId != expectedUserId) return null
        return ExpectedPaymentsStateMapper.map(observation, start, endInclusive)
            .copy(userId = expectedUserId)
    }
}

/** Maps one repository snapshot into immutable, filtered presentation state. */
internal object ExpectedPaymentsStateMapper {
    fun map(
        observation: Observation<List<ExpectedPayment>>,
        start: LocalDate,
        endInclusive: LocalDate,
    ): ExpectedPaymentsUiState = when (observation) {
        Observation.Loading -> ExpectedPaymentsUiState()
        is Observation.Data -> filter(dataState(observation.value, observation.metadata), start, endInclusive)
        is Observation.Failed -> observation.lastAvailable?.let { last ->
            filter(dataState(last.value, last.metadata), start, endInclusive).copy(error = observation.failure)
        } ?: ExpectedPaymentsUiState(loading = false, error = observation.failure)
    }

    private fun dataState(
        payments: List<ExpectedPayment>,
        metadata: ObservationMetadata,
    ) = ExpectedPaymentsUiState(
        loading = false,
        payments = payments,
        metadata = metadata,
    )

    private fun filter(
        state: ExpectedPaymentsUiState,
        start: LocalDate,
        endInclusive: LocalDate,
    ): ExpectedPaymentsUiState = try {
        val payments = activeExpectedPaymentsDueIn(state.payments, start, endInclusive)
        state.copy(
            payments = payments,
            totalsByCurrency = activeExpectedPaymentTotalsByCurrency(payments),
        )
    } catch (error: IllegalArgumentException) {
        ExpectedPaymentsUiState(
            loading = false,
            error = RepositoryFailure(
                FailureCode.VALIDATION,
                error.message ?: "Expected-payment data is invalid.",
            ),
            metadata = state.metadata,
        )
    }
}
