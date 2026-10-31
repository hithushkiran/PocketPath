package com.pocketpath.feature.income

import com.pocketpath.domain.calculation.activeExpectedPaymentOutstandingTotalsByCurrency
import com.pocketpath.domain.calculation.activeExpectedPaymentsDueIn
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.StableIds
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
import com.pocketpath.domain.repository.CompleteLedger
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
    val accountsLoading: Boolean = true,
    val accountsError: RepositoryFailure? = null,
    val selectedReceivingAccountId: String? = null,
    val totalsByCurrency: Map<FiatCurrency, Money> = emptyMap(),
    val error: RepositoryFailure? = null,
    val ledgerLoading: Boolean = true,
    val ledgerError: RepositoryFailure? = null,
    val metadata: ObservationMetadata? = null,
    val cancellingPaymentId: String? = null,
    val cancellationError: RepositoryFailure? = null,
    /** An online cancellation may have reached Firestore; retain its operation ID for Retry. */
    val hasUnresolvedCancellationRetry: Boolean = false,
    val cancellationMessage: String? = null,
    val recordingReceiptPaymentId: String? = null,
    val receiptError: RepositoryFailure? = null,
    val hasUnresolvedReceiptRetry: Boolean = false,
    val receiptMessage: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && ledgerError == null && payments.isEmpty()
    val isPopulated: Boolean get() = !loading && payments.isNotEmpty()
    val canRecordReceipts: Boolean
        get() = !loading && !ledgerLoading && ledgerError == null &&
            !accountsLoading && accountsError == null && accounts.any(::isActiveLkrAccount)
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
        require(operationId == StableIds.expectedPaymentReceipt(payment.id, payment.metadata.revision)) {
            "Receipt ID must match the expected-payment revision."
        }
        val account = requireNotNull(accountId) { "Select a receiving account." }
        require(payment.state == com.pocketpath.domain.model.ExpectedPaymentState.ACTIVE)
        require(accounts.any { it.id == account && isActiveLkrAccount(it) })
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

    fun withAccounts(
        state: ExpectedPaymentsUiState,
        observation: Observation<List<Account>>,
    ): ExpectedPaymentsUiState = when (observation) {
        Observation.Loading -> state.copy(accountsLoading = state.accounts.isEmpty() && state.accountsLoading)
        is Observation.Data -> state.withReceivingAccounts(observation.value, loading = false, error = null)
        is Observation.Failed -> {
            val accounts = observation.lastAvailable?.value ?: state.accounts
            state.withReceivingAccounts(accounts, loading = false, error = observation.failure)
        }
    }

    fun selectReceivingAccount(state: ExpectedPaymentsUiState, accountId: String?): ExpectedPaymentsUiState {
        val selected = state.accounts.firstOrNull { it.id == accountId && isActiveLkrAccount(it) }?.id
        return state.copy(selectedReceivingAccountId = selected)
    }

    fun mapIfCurrent(
        activeUserId: String?,
        expectedUserId: String,
        observation: Observation<List<ExpectedPayment>>,
        ledger: Observation<CompleteLedger>,
        start: LocalDate,
        endInclusive: LocalDate,
    ): ExpectedPaymentsUiState? {
        if (activeUserId != expectedUserId) return null
        return ExpectedPaymentsStateMapper.map(observation, ledger, start, endInclusive)
            .copy(userId = expectedUserId)
    }

    fun preserveTransientState(
        mapped: ExpectedPaymentsUiState,
        current: ExpectedPaymentsUiState,
    ): ExpectedPaymentsUiState = mapped.copy(
        accounts = current.accounts,
        accountsLoading = current.accountsLoading,
        accountsError = current.accountsError,
        selectedReceivingAccountId = current.selectedReceivingAccountId,
        cancellingPaymentId = current.cancellingPaymentId,
        cancellationError = current.cancellationError,
        hasUnresolvedCancellationRetry = current.hasUnresolvedCancellationRetry,
        cancellationMessage = current.cancellationMessage,
        recordingReceiptPaymentId = current.recordingReceiptPaymentId,
        receiptError = current.receiptError,
        hasUnresolvedReceiptRetry = current.hasUnresolvedReceiptRetry,
        receiptMessage = current.receiptMessage,
    )

    private fun ExpectedPaymentsUiState.withReceivingAccounts(
        accounts: List<Account>,
        loading: Boolean,
        error: RepositoryFailure?,
    ): ExpectedPaymentsUiState {
        val activeAccounts = accounts
        val selected = selectedReceivingAccountId
            ?.takeIf { id -> activeAccounts.any { it.id == id && isActiveLkrAccount(it) } }
            ?: activeAccounts.firstOrNull(::isActiveLkrAccount)?.id
        return copy(
            accounts = activeAccounts,
            accountsLoading = loading,
            accountsError = error,
            selectedReceivingAccountId = selected,
        )
    }
}

/** Maps one repository snapshot into immutable, filtered presentation state. */
internal object ExpectedPaymentsStateMapper {
    fun map(
        observation: Observation<List<ExpectedPayment>>,
        ledger: Observation<CompleteLedger>,
        start: LocalDate,
        endInclusive: LocalDate,
    ): ExpectedPaymentsUiState {
        val unfiltered = when (observation) {
        Observation.Loading -> ExpectedPaymentsUiState()
        is Observation.Data -> filter(dataState(observation.value, observation.metadata), start, endInclusive)
        is Observation.Failed -> observation.lastAvailable?.let { last ->
            filter(dataState(last.value, last.metadata), start, endInclusive).copy(error = observation.failure)
        } ?: ExpectedPaymentsUiState(loading = false, error = observation.failure)
        }
        val allPayments = when (observation) {
            is Observation.Data -> observation.value
            is Observation.Failed -> observation.lastAvailable?.value.orEmpty()
            Observation.Loading -> emptyList()
        }
        return withLedger(unfiltered, allPayments, ledger)
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

    private fun withLedger(
        state: ExpectedPaymentsUiState,
        allPayments: List<ExpectedPayment>,
        ledger: Observation<CompleteLedger>,
    ): ExpectedPaymentsUiState = when (ledger) {
        Observation.Loading -> state.copy(loading = true, ledgerLoading = true)
        is Observation.Data -> reconcile(state, allPayments, ledger.value, loading = false, error = null)
        is Observation.Failed -> ledger.lastAvailable?.let { last ->
            reconcile(state, allPayments, last.value, loading = false, error = ledger.failure)
        } ?: state.copy(loading = false, ledgerLoading = false, ledgerError = ledger.failure)
    }

    private fun reconcile(
        state: ExpectedPaymentsUiState,
        payments: List<ExpectedPayment>,
        ledger: CompleteLedger,
        loading: Boolean,
        error: RepositoryFailure?,
    ): ExpectedPaymentsUiState = try {
        state.copy(
            loading = state.loading || loading,
            ledgerLoading = loading,
            ledgerError = error,
            totalsByCurrency = activeExpectedPaymentOutstandingTotalsByCurrency(payments, ledger),
        )
    } catch (failure: IllegalArgumentException) {
        state.copy(
            loading = false,
            ledgerLoading = false,
            ledgerError = RepositoryFailure(
                FailureCode.VALIDATION,
                failure.message ?: "Expected-payment receipt data is invalid.",
            ),
            totalsByCurrency = emptyMap(),
        )
    } catch (failure: ArithmeticException) {
        state.copy(
            loading = false,
            ledgerLoading = false,
            ledgerError = RepositoryFailure(FailureCode.VALIDATION, "Expected-payment totals exceed supported bounds."),
            totalsByCurrency = emptyMap(),
        )
    }
}

internal fun isActiveLkrAccount(account: Account): Boolean =
    account.asset == FiatCurrency.LKR && account.metadata.archivedAt == null
