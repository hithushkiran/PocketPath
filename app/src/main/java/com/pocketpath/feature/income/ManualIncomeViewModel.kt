package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.data.repository.FirebaseIncomeRepositoryFactory
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.IncomeRepository
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.WriteState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ManualIncomeViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val foundationFactory: FirebaseFoundationFactory,
    private val incomeFactory: FirebaseIncomeRepositoryFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ManualIncomeUiState())
    val state = _state.asStateFlow()

    private var generation = 0
    private var activeUserId: String? = null
    private var sessionScope: CoroutineScope? = null
    private var incomeRepository: IncomeRepository? = null
    private var zone = ZoneId.of("Asia/Colombo")
    private var frozenEvent: FinancialTransaction? = null
    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                val epoch = ++generation
                sessionScope?.cancel()
                writeJob?.cancel()
                sessionScope = null
                incomeRepository = null
                frozenEvent = null
                activeUserId = null
                zone = ZoneId.of("Asia/Colombo")

                if (session !is AuthSession.SignedIn) {
                    _state.value = ManualIncomeUiState(loading = false)
                    return@collectLatest
                }

                val uid = session.user.uid
                activeUserId = uid
                _state.value = ManualIncomeUiState(userId = uid, date = today(), loading = true)
                try {
                    kotlinx.coroutines.coroutineScope {
                        sessionScope = this
                        val foundation = foundationFactory.current()
                        val income = incomeFactory.current()
                        check(foundation.userId == uid && income.userId == uid)
                        incomeRepository = income
                        zone = foundation.cachedProfile()?.timeZone ?: ZoneId.of("Asia/Colombo")
                        when (val profile = foundation.ensureProfile()) {
                            is CommandResult.Accepted -> zone = profile.value.timeZone
                            is CommandResult.Rejected -> Unit
                        }
                        _state.update { it.copy(date = today()) }

                        combine(foundation.observeAccounts(), income.observeSources()) { accounts, sources ->
                            accounts to sources
                        }.collect { (accounts, sources) ->
                            if (epoch != generation) return@collect
                            updateReferences(accounts, sources)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (epoch == generation) {
                        _state.update { it.copy(loading = false, error = error.message ?: "Unable to load income references.") }
                    }
                }
            }
        }
    }

    fun onEvent(expectedUserId: String, event: ManualIncomeEvent) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        when (event) {
            is ManualIncomeEvent.SourceChanged -> edit { it.copy(sourceId = event.id) }
            is ManualIncomeEvent.AccountChanged -> edit { it.copy(accountId = event.id) }
            is ManualIncomeEvent.AmountChanged -> edit { it.copy(amount = event.value) }
            is ManualIncomeEvent.DateChanged -> edit { it.copy(date = event.value) }
            is ManualIncomeEvent.NoteChanged -> edit { it.copy(note = event.value) }
            ManualIncomeEvent.Save -> save()
            ManualIncomeEvent.RecordAnother -> recordAnother()
        }
    }

    private fun updateReferences(accounts: Observation<List<Account>>, sources: Observation<List<IncomeSource>>) {
        val accountData = accounts as? Observation.Data
        val sourceData = sources as? Observation.Data
        val failure = (accounts as? Observation.Failed)?.failure ?: (sources as? Observation.Failed)?.failure
        _state.update { old ->
            val availableAccounts = accountData?.value ?: old.accounts
            val availableSources = sourceData?.value ?: old.sources
            val selectableAccounts = availableAccounts.filter { it.asset == com.pocketpath.domain.money.FiatCurrency.LKR && it.metadata.archivedAt == null }
            val selectableSources = availableSources.filter {
                it.metadata.archivedAt == null &&
                    (it.defaultCurrency == null || it.defaultCurrency == com.pocketpath.domain.money.FiatCurrency.LKR)
            }
            if (old.locked || old.saved) {
                old.copy(
                    loading = accounts is Observation.Loading || sources is Observation.Loading,
                    referencesReady = accountData != null && sourceData != null,
                    accounts = availableAccounts,
                    sources = availableSources,
                    error = failure?.message ?: old.error,
                )
            } else {
                old.copy(
                    loading = accounts is Observation.Loading || sources is Observation.Loading,
                    referencesReady = accountData != null && sourceData != null,
                    accounts = availableAccounts,
                    sources = availableSources,
                    accountId = old.accountId?.takeIf { id -> selectableAccounts.any { it.id == id } }
                        ?: selectableAccounts.firstOrNull()?.id,
                    sourceId = old.sourceId?.takeIf { id -> selectableSources.any { it.id == id } }
                        ?: selectableSources.firstOrNull()?.id,
                    error = failure?.message,
                )
            }
        }
    }

    private fun edit(change: (ManualIncomeUiState) -> ManualIncomeUiState) {
        val current = _state.value
        if (current.busy || current.locked || current.saved) return
        _state.update { change(it).copy(error = null, writeState = null, replayed = false) }
    }

    private fun save() {
        val current = _state.value
        if (!current.canStartSave()) return
        val repository = incomeRepository ?: return
        val scope = sessionScope ?: return
        val epoch = generation
        val event = frozenEvent ?: try {
            ManualIncomeEventFactory.create(
                operationId = UUID.randomUUID().toString(),
                sourceId = current.sourceId,
                accountId = current.accountId,
                amountText = current.amount,
                dateText = current.date,
                noteText = current.note,
                zone = zone,
                now = Instant.now(),
                accounts = current.accounts,
                sources = current.sources,
            )
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "Enter valid income details.") }
            return
        }

        frozenEvent = event
        _state.update { it.copy(busy = true, locked = true, error = null) }
        writeJob = scope.launch {
            try {
                when (val result = repository.recordIncome(event)) {
                    is CommandResult.Accepted -> {
                        if (epoch == generation) {
                            _state.update {
                                it.copy(
                                    busy = false,
                                    locked = true,
                                    saved = true,
                                    replayed = result.replayed,
                                    writeState = result.writeState,
                                    error = null,
                                )
                            }
                        }
                    }
                    is CommandResult.Rejected -> handleRejected(epoch, result.failure.code, result.failure.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) {
                    _state.update { it.copy(busy = false, locked = true, error = "Save not confirmed. Reconnect and retry.") }
                }
            }
        }
    }

    private fun handleRejected(epoch: Int, code: FailureCode, message: String) {
        if (epoch != generation) return
        val uncertain = code == FailureCode.NETWORK || code == FailureCode.REJECTED
        if (!uncertain) frozenEvent = null
        _state.update {
            it.copy(
                busy = false,
                locked = uncertain,
                writeState = if (uncertain) null else WriteState.REJECTED,
                error = message,
            )
        }
    }

    private fun recordAnother() {
        if (!_state.value.saved) return
        frozenEvent = null
        _state.update {
            it.copy(
                amount = "",
                date = today(),
                note = "",
                busy = false,
                locked = false,
                saved = false,
                replayed = false,
                writeState = null,
                error = null,
            )
        }
    }

    private fun today(): String = LocalDate.now(zone).toString()
}
