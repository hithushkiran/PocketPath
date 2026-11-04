package com.pocketpath.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GoalsUiState(
    val userId: String? = null,
    val form: GoalForm = GoalForm(),
    val errors: Map<GoalField, String> = emptyMap(),
    val preview: GoalDraftPreview? = null,
)

sealed interface GoalsEvent {
    data class Edit(val field: GoalField, val value: String) : GoalsEvent
    data object PreviewDraft : GoalsEvent
    data object ClearDraft : GoalsEvent
}

@HiltViewModel
class GoalsViewModel(
    auth: AuthRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(auth: AuthRepository) : this(
        auth,
        Clock.system(ZoneId.of("Asia/Colombo")),
    )

    private val _state = MutableStateFlow(GoalsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auth.session.collect { session ->
                val uid = (session as? AuthSession.SignedIn)?.user?.uid
                if (uid != _state.value.userId) {
                    _state.value = if (uid == null) {
                        GoalsUiState()
                    } else {
                        GoalsUiState(userId = uid, form = newGoalForm(LocalDate.now(clock)))
                    }
                }
            }
        }
    }

    fun onEvent(expectedUserId: String, event: GoalsEvent) {
        val current = _state.value
        if (expectedUserId.isBlank() || current.userId != expectedUserId) return

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
            GoalsEvent.PreviewDraft -> when (
                val result = validateGoalForm(current.form, LocalDate.now(clock))
            ) {
                is GoalFormResult.Invalid -> current.copy(errors = result.errors, preview = null)
                is GoalFormResult.Valid -> current.copy(errors = emptyMap(), preview = result.preview)
            }
        }
    }
}
