package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseIncomeRepositoryFactory
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.FailureCode
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ExpectedPaymentCreationViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val incomeFactory: FirebaseIncomeRepositoryFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpectedPaymentCreationUiState(loading = false))
    val state = _state.asStateFlow()
    private var generation = 0
    private var activeUserId: String? = null
    private var sessionScope: CoroutineScope? = null
    private var repository: com.pocketpath.domain.repository.IncomeRepository? = null
    private var frozen: ExpectedPayment? = null
    private var writeJob: Job? = null

    init { viewModelScope.launch { auth.session.collectLatest { session ->
        val epoch = ++generation
        sessionScope?.cancel(); writeJob?.cancel(); sessionScope = null; repository = null; frozen = null; activeUserId = null
        if (session !is AuthSession.SignedIn) { _state.value = ExpectedPaymentCreationUiState(loading = false); return@collectLatest }
        val uid = session.user.uid; activeUserId = uid; _state.value = ExpectedPaymentCreationUiState(userId = uid)
        try { kotlinx.coroutines.coroutineScope {
            sessionScope = this
            val current = incomeFactory.current(); check(current.userId == uid); repository = current
            current.observeSources().collect { observation ->
                if (epoch != generation || activeUserId != uid) return@collect
                when (observation) {
                    Observation.Loading -> _state.update { it.copy(loading = true) }
                    is Observation.Data -> _state.update { old ->
                        val eligible = observation.value.filter { it.metadata.archivedAt == null && (it.defaultCurrency == null || it.defaultCurrency == com.pocketpath.domain.money.FiatCurrency.LKR) }
                        old.copy(loading = false, sources = observation.value, sourceId = old.sourceId?.takeIf { id -> eligible.any { it.id == id } } ?: eligible.firstOrNull()?.id, error = null)
                    }
                    is Observation.Failed -> _state.update { it.copy(loading = false, error = observation.failure.message) }
                }
            }
        }} catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            if (epoch == generation && activeUserId == uid) _state.update { it.copy(loading = false, error = error.message ?: "Unable to load income sources.") }
        }
    } } }

    fun onEvent(expectedUserId: String, event: ExpectedPaymentCreationEvent) {
        if (expectedUserId != activeUserId || expectedUserId != _state.value.userId) return
        when (event) {
            is ExpectedPaymentCreationEvent.SourceChanged -> edit { it.copy(sourceId = event.id) }
            is ExpectedPaymentCreationEvent.ProjectChanged -> edit { it.copy(projectLabel = event.value) }
            is ExpectedPaymentCreationEvent.MilestoneChanged -> edit { it.copy(milestoneLabel = event.value) }
            is ExpectedPaymentCreationEvent.AmountChanged -> edit { it.copy(amount = event.value) }
            is ExpectedPaymentCreationEvent.DueDateChanged -> edit { it.copy(dueDate = event.value) }
            ExpectedPaymentCreationEvent.Save -> save()
            ExpectedPaymentCreationEvent.CreateAnother -> if (_state.value.saved) { frozen = null; _state.update { ExpectedPaymentCreationUiState(userId = it.userId, sources = it.sources, sourceId = it.eligibleSources.firstOrNull()?.id, loading = false) } }
        }
    }
    private fun edit(change: (ExpectedPaymentCreationUiState) -> ExpectedPaymentCreationUiState) { if (!_state.value.busy && !_state.value.locked && !_state.value.saved) _state.update { change(it).copy(error = null, replayed = false) } }
    private fun save() {
        val current = _state.value; if (!current.canStartSave()) return
        val repo = repository ?: return; val scope = sessionScope ?: return; val epoch = generation
        val payment = frozen ?: try { ExpectedPaymentCreationFactory.create(UUID.randomUUID().toString(), current.sourceId, current.projectLabel, current.milestoneLabel, current.amount, current.dueDate, current.sources) } catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Enter valid expected-payment details.") }; return }
        frozen = payment; _state.update { it.copy(busy = true, locked = true, error = null) }
        writeJob = scope.launch { try { when (val result = repo.saveExpectedPayment(payment.id, payment)) {
            is CommandResult.Accepted -> if (epoch == generation) _state.update { it.copy(busy = false, saved = true, replayed = result.replayed, writeState = result.writeState) }
            is CommandResult.Rejected -> reject(epoch, result.failure.code, result.failure.message)
        }} catch (c: CancellationException) { throw c } catch (_: Exception) { if (epoch == generation) _state.update { it.copy(busy = false, locked = true, error = "Save not confirmed. Reconnect and retry.") } } }
    }
    private fun reject(epoch: Int, code: FailureCode, message: String) { if (epoch != generation) return; val uncertain = code == FailureCode.NETWORK || code == FailureCode.REJECTED; if (!uncertain) frozen = null; _state.update { it.copy(busy = false, locked = uncertain, writeState = if (uncertain) null else WriteState.REJECTED, error = message) } }
}
