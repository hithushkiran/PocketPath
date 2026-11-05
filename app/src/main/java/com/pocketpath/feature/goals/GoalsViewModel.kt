package com.pocketpath.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.data.repository.FirebaseFoundationRepository
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.Goal
import com.pocketpath.domain.model.ReservationEffect
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoalsUiState(
    val userId: String? = null,
    val form: GoalForm = GoalForm(),
    val errors: Map<GoalField, String> = emptyMap(),
    val preview: GoalDraftPreview? = null,
    val goals: List<Goal> = emptyList(),
    val goalsLoading: Boolean = false,
    val goalsError: String? = null,
    val busy: Boolean = false,
    val saveError: String? = null,
    val retryGoal: Boolean = false,
    val contribution: GoalContributionForm? = null,
    val showContribution: Boolean = false,
) {
    val hasUnresolvedWrite: Boolean
        get() = busy || retryGoal || contribution?.retryRequired == true
}

data class GoalContributionForm(
    val goalId: String,
    val accountId: String,
    val amount: String = "",
    val error: String? = null,
    val retryRequired: Boolean = false,
)

sealed interface GoalsEvent {
    data class Edit(val field: GoalField, val value: String) : GoalsEvent
    data object PreviewDraft : GoalsEvent
    data object ClearDraft : GoalsEvent
    data object SaveGoal : GoalsEvent
    data object ReloadGoals : GoalsEvent
    data class OpenContribution(val goalId: String, val accountId: String) : GoalsEvent
    data object CloseContribution : GoalsEvent
    data class EditContribution(val accountId: String? = null, val amount: String? = null) : GoalsEvent
    data object SaveContribution : GoalsEvent
}

@HiltViewModel
class GoalsViewModel(
    auth: AuthRepository,
    private val clock: Clock,
    private val factory: FirebaseFoundationFactory? = null,
) : ViewModel() {
    @Inject
    constructor(auth: AuthRepository, factory: FirebaseFoundationFactory) : this(
        auth,
        Clock.system(ZoneId.of("Asia/Colombo")),
        factory,
    )

    private val _state = MutableStateFlow(GoalsUiState())
    val state = _state.asStateFlow()
    private var observationJob: Job? = null
    private var writeJob: Job? = null
    private var pendingGoal: Goal? = null
    private var pendingContribution: FinancialTransaction? = null

    init {
        viewModelScope.launch {
            auth.session.collect { session ->
                val uid = (session as? AuthSession.SignedIn)?.user?.uid
                if (uid != _state.value.userId) {
                    observationJob?.cancel()
                    writeJob?.cancel()
                    pendingGoal = null
                    pendingContribution = null
                    _state.value = if (uid == null) {
                        GoalsUiState()
                    } else {
                        GoalsUiState(userId = uid, form = newGoalForm(LocalDate.now(clock)))
                    }
                    if (uid != null) loadGoals(uid)
                }
            }
        }
    }

    fun onEvent(expectedUserId: String, event: GoalsEvent) {
        val current = _state.value
        if (expectedUserId.isBlank() || current.userId != expectedUserId || current.busy) return
        if (current.retryGoal && event !in listOf(GoalsEvent.SaveGoal, GoalsEvent.ReloadGoals)) return

        _state.value = when (event) {
            is GoalsEvent.Edit -> current.copy(
                form = current.form.edited(event.field, event.value),
                errors = emptyMap(),
                preview = null,
            )
            GoalsEvent.ClearDraft -> current.copy(
                form = newGoalForm(LocalDate.now(clock)),
                errors = emptyMap(),
                preview = null,
            )
            GoalsEvent.SaveGoal -> return saveGoal(current, expectedUserId)
            GoalsEvent.ReloadGoals -> return loadGoals(expectedUserId)
            is GoalsEvent.OpenContribution -> {
                if (current.goals.none { it.id == event.goalId }) return
                if (current.contribution != null && current.contribution.goalId != event.goalId) return
                current.copy(
                    contribution = current.contribution ?: GoalContributionForm(event.goalId, event.accountId),
                    showContribution = true,
                )
            }
            GoalsEvent.CloseContribution -> current.copy(
                contribution = current.contribution?.takeIf { it.retryRequired },
                showContribution = false,
            )
            is GoalsEvent.EditContribution -> {
                val form = current.contribution ?: return
                if (form.retryRequired) return
                current.copy(contribution = form.copy(
                    accountId = event.accountId ?: form.accountId,
                    amount = event.amount ?: form.amount,
                    error = null,
                ))
            }
            GoalsEvent.SaveContribution -> return saveContribution(current, expectedUserId)
            GoalsEvent.PreviewDraft -> when (
                val result = validateGoalForm(current.form, LocalDate.now(clock))
            ) {
                is GoalFormResult.Invalid -> current.copy(errors = result.errors, preview = null)
                is GoalFormResult.Valid -> current.copy(errors = emptyMap(), preview = result.preview)
            }
        }
    }

    private fun repositoryFor(uid: String): FirebaseFoundationRepository? =
        runCatching { factory?.current() }.getOrNull()?.takeIf { it.userId == uid }

    private fun loadGoals(uid: String) {
        val repository = repositoryFor(uid) ?: return
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            repository.observeGoals().collect { observation ->
                _state.update { current ->
                    if (current.userId != uid) current
                    else when (observation) {
                        Observation.Loading -> current.copy(goalsLoading = true, goalsError = null)
                        is Observation.Data -> current.copy(goals = observation.value, goalsLoading = false, goalsError = null)
                        is Observation.Failed -> current.copy(goalsLoading = false, goalsError = observation.failure.message)
                    }
                }
            }
        }
    }

    private fun saveGoal(current: GoalsUiState, uid: String) {
        val repository = repositoryFor(uid) ?: return
        val goal = pendingGoal ?: when (val result = validateGoalForm(current.form, LocalDate.now(clock))) {
            is GoalFormResult.Invalid -> {
                _state.value = current.copy(errors = result.errors, preview = null)
                return
            }
            is GoalFormResult.Valid -> with(result.preview) {
                Goal(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    target = EntryAmount(Money.lkr(targetLkrMinor)),
                    startDate = startDate,
                    deadline = deadline,
                    firstContributionDate = firstContributionDate,
                )
            }
        }
        pendingGoal = goal
        _state.value = current.copy(busy = true, saveError = null, errors = emptyMap())
        writeJob = viewModelScope.launch {
            val result = repository.saveGoal(goal)
            if (_state.value.userId != uid) return@launch
            when (result) {
                is CommandResult.Accepted -> {
                    pendingGoal = null
                    _state.update { it.copy(
                        busy = false, retryGoal = false, preview = null, form = newGoalForm(LocalDate.now(clock)),
                    ) }
                }
                is CommandResult.Rejected -> {
                    val retry = result.failure.code in setOf(FailureCode.NETWORK, FailureCode.REJECTED)
                    if (!retry) pendingGoal = null
                    _state.update { it.copy(busy = false, retryGoal = retry, saveError = result.failure.message) }
                }
            }
        }
    }

    private fun saveContribution(current: GoalsUiState, uid: String) {
        if (!current.showContribution) return
        val form = current.contribution ?: return
        val repository = repositoryFor(uid) ?: return
        val event = pendingContribution ?: run {
            val amount = runCatching { EntryAmount(Money.parse(form.amount.trim(), FiatCurrency.LKR)) }.getOrNull()
            if (amount == null || form.accountId.isBlank()) {
                _state.value = current.copy(contribution = form.copy(error = "Choose an account and enter a positive LKR amount."))
                return
            }
            val goal = current.goals.firstOrNull { it.id == form.goalId } ?: return
            val now = clock.instant()
            val date = now.atZone(clock.zone).toLocalDate()
            FinancialTransaction(
                operationId = UUID.randomUUID().toString(),
                kind = TransactionKind.GOAL_RESERVE,
                status = TransactionStatus.POSTED,
                financialTime = FinancialTime(now, date, clock.zone),
                clientCreatedAt = now,
                movements = emptyList(),
                reservationEffects = listOf(ReservationEffect(goal.id, form.accountId, amount.money)),
                note = goal.name,
            )
        }
        pendingContribution = event
        _state.value = current.copy(busy = true, contribution = form.copy(error = null))
        writeJob = viewModelScope.launch {
            val result = repository.reserveForGoal(event)
            if (_state.value.userId != uid) return@launch
            when (result) {
                is CommandResult.Accepted -> {
                    pendingContribution = null
                    _state.update { it.copy(busy = false, contribution = null, showContribution = false) }
                }
                is CommandResult.Rejected -> {
                    val retry = result.failure.code in setOf(FailureCode.NETWORK, FailureCode.REJECTED)
                    if (!retry) pendingContribution = null
                    _state.update { it.copy(busy = false, contribution = form.copy(
                        retryRequired = retry, error = result.failure.message,
                    )) }
                }
            }
        }
    }
}
