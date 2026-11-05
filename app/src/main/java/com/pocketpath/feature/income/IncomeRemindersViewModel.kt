package com.pocketpath.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.data.repository.FirebaseFoundationFactory
import com.pocketpath.data.repository.FirebaseFoundationRepository
import com.pocketpath.domain.calculation.IncomeReminderAction
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.DataOrigin
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IncomeRemindersUiState(
    val userId: String? = null,
    val schedules: List<Schedule> = emptyList(),
    val occurrences: List<Occurrence> = emptyList(),
    val ready: Boolean = false,
    val cached: Boolean = false,
    val loadError: String? = null,
    val busy: Boolean = false,
    val retry: Boolean = false,
    val message: String? = null,
    val writeError: String? = null,
) {
    val canEdit: Boolean get() = ready && !busy && !retry
    val canWrite: Boolean get() = canEdit && !cached
    val hasUnresolvedWrite: Boolean get() = busy || retry
}

@HiltViewModel
class IncomeRemindersViewModel @Inject constructor(
    auth: AuthRepository,
    private val factory: FirebaseFoundationFactory,
) : ViewModel() {
    private val mutableState = MutableStateFlow(IncomeRemindersUiState())
    val state = mutableState.asStateFlow()
    private var scope: CoroutineScope? = null
    private var repository: FirebaseFoundationRepository? = null
    private var generation = 0
    private var pending: (suspend FirebaseFoundationRepository.() -> CommandResult<Unit>)? = null

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                val epoch = ++generation
                scope = null
                repository = null
                pending = null
                val uid = (session as? AuthSession.SignedIn)?.user?.uid
                mutableState.value = IncomeRemindersUiState(userId = uid)
                if (uid == null) return@collectLatest
                try {
                    coroutineScope {
                        val current = factory.current()
                        check(current.userId == uid)
                        scope = this
                        repository = current
                        combine(
                            current.observeIncomeReminderSchedules(),
                            current.observeIncomeReminderOccurrences(),
                        ) { schedules, occurrences -> schedules to occurrences }
                            .collect { (schedules, occurrences) ->
                                if (generation != epoch) return@collect
                                mutableState.update { previous ->
                                    val scheduleData = schedules as? Observation.Data
                                    val occurrenceData = occurrences as? Observation.Data
                                    previous.copy(
                                        schedules = scheduleData?.value ?: previous.schedules,
                                        occurrences = occurrenceData?.value ?: previous.occurrences,
                                        ready = scheduleData != null && occurrenceData != null,
                                        cached = scheduleData?.metadata?.origin == DataOrigin.CACHE ||
                                            occurrenceData?.metadata?.origin == DataOrigin.CACHE,
                                        loadError = (schedules as? Observation.Failed)?.failure?.message
                                            ?: (occurrences as? Observation.Failed)?.failure?.message,
                                    )
                                }
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (epoch == generation) mutableState.update {
                        it.copy(ready = false, loadError = "Could not load recurring income.")
                    }
                }
            }
        }
    }

    fun save(uid: String, schedule: Schedule) = start(uid) { saveIncomeReminderSchedule(schedule) }

    fun act(uid: String, occurrence: Occurrence, action: IncomeReminderAction) = start(uid) {
        val revision = occurrence.metadata.revision.takeIf { occurrence.metadata.createdAt != null }
        actOnIncomeReminder(occurrence.scheduleId, occurrence.nominalDate, revision, action)
    }

    private fun start(uid: String, operation: suspend FirebaseFoundationRepository.() -> CommandResult<Unit>) {
        val current = mutableState.value
        if (current.userId != uid || !current.canWrite || scope == null || repository == null) return
        pending = operation
        execute(uid)
    }

    fun retry(uid: String) {
        if (mutableState.value.retry) execute(uid)
    }

    private fun execute(uid: String) {
        val current = mutableState.value
        if (current.userId != uid || current.busy) return
        val work = pending ?: return
        val repo = repository ?: return
        val session = scope ?: return
        val epoch = generation
        mutableState.update { it.copy(busy = true, retry = false, message = null, writeError = null) }
        session.launch {
            val result = work(repo)
            if (generation != epoch) return@launch
            when (result) {
                is CommandResult.Accepted -> {
                    pending = null
                    mutableState.update { it.copy(busy = false, message = "Saved.") }
                }
                is CommandResult.Rejected -> {
                    val retry = result.failure.code in setOf(FailureCode.NETWORK, FailureCode.REJECTED)
                    if (!retry) pending = null
                    mutableState.update { it.copy(busy = false, retry = retry, writeError = result.failure.message) }
                }
            }
        }
    }
}
