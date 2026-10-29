package com.pocketpath.feature.income

import com.pocketpath.domain.calculation.activeExpectedPaymentTotalsByCurrency
import com.pocketpath.domain.calculation.activeExpectedPaymentsDueIn
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.NativeAmount
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
    val accounts: List<Account> = emptyList(),
    val totalsByCurrency: Map<FiatCurrency, Money> = emptyMap(),
    val error: RepositoryFailure? = null,
    val metadata: ObservationMetadata? = null,
    val cancellingPaymentId: String? = null,
    val cancellationError: RepositoryFailure? = null,
    /** An online cancellation may have reached Firestore; retain its operation ID for Retry. */
    val hasUnresolvedCancellationRetry: Boolean = false,
    val cancellationMessage: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && payments.isEmpty()
    val isPopulated: Boolean get() = !loading && payments.isNotEmpty()
}

/** Constructs the same posted LKR INCOME event as P02A, with the expected-payment audit link. */
internal object ExpectedPaymentReceiptFactory {
    fun create(
        operationId: String,
        payment: ExpectedPayment,
        accountId: String?,
        amountText: String,
        accounts: List<Account>,
        now: java.time.Instant,
        zone: java.time.ZoneId,
    ): FinancialTransaction {
        val account = requireNotNull(accountId) { "Select a receiving account." }
        require(payment.state == com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE)
        require(accounts.any { it.id == account && it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null })
        val amount = Money.parse(amountText.trim(), FiatCurrency.LKR)
        require(amount.minor > 0 && amount.minor <= payment.amountDue.money.minor - payment.receivedAmountMinor) {
            "Receipt exceeds the remaining expected amount."
        }
        val date = now.atZone(zone).toLocalDate()
        return FinancialTransaction(
            operationId = operationId, kind = TransactionKind.INCOME, status = TransactionStatus.POSTED,
            financialTime = FinancialTime(now, date, zone), clientCreatedAt = now,
            movements = listOf(AccountMovement(account, NativeAmount.Fiat(amount))),
            components = listOf(ReportComponent(ComponentNature.EARNING, KnownLkrAmount.Known(amount), incomeSourceId = payment.incomeSourceId)),
            expectedPaymentId = payment.id,
        )
    }
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
