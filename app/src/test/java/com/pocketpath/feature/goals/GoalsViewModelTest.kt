package com.pocketpath.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.pocketpath.domain.repository.AuthOperationResult
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.AuthenticatedUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val auth = FakeAuth()
    private lateinit var viewModel: GoalsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                GoalsViewModel(
                    auth,
                    Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneId.of("Asia/Colombo")),
                ),
            )!!
        })[GoalsViewModel::class.java]
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun draftPreviewInvalidatesOnEditsAndErrorsDoNotLeaveOldResults() = runTest(dispatcher) {
        auth.session.value = signedIn("a")
        runCurrent()
        viewModel.onEvent("a", GoalsEvent.Edit(GoalField.NAME, "MacBook"))
        viewModel.onEvent("a", GoalsEvent.Edit(GoalField.TARGET, "490000"))
        viewModel.onEvent("a", GoalsEvent.PreviewDraft)
        assertNotNull(viewModel.state.value.preview)
        viewModel.onEvent("a", GoalsEvent.Edit(GoalField.TARGET, "1.001"))
        assertNull(viewModel.state.value.preview)
        viewModel.onEvent("a", GoalsEvent.PreviewDraft)
        assertTrue(GoalField.TARGET in viewModel.state.value.errors)
        assertNull(viewModel.state.value.preview)
        viewModel.onEvent("a", GoalsEvent.ClearDraft)
        assertEquals("", viewModel.state.value.form.name)
        assertEquals("2026-10-01", viewModel.state.value.form.startDate)
        assertTrue(viewModel.state.value.errors.isEmpty())
    }

    @Test fun userChangesClearDraftAndStaleUserEventsAreIgnored() = runTest(dispatcher) {
        auth.session.value = signedIn("a")
        runCurrent()
        viewModel.onEvent("a", GoalsEvent.Edit(GoalField.NAME, "Private draft"))
        auth.session.value = signedIn("a", "updated@example.test")
        runCurrent()
        assertEquals("Private draft", viewModel.state.value.form.name)
        auth.session.value = signedIn("b")
        runCurrent()
        assertEquals("b", viewModel.state.value.userId)
        assertEquals("", viewModel.state.value.form.name)
        viewModel.onEvent("a", GoalsEvent.Edit(GoalField.NAME, "Stale event"))
        assertEquals("", viewModel.state.value.form.name)
        viewModel.onEvent("b", GoalsEvent.Edit(GoalField.NAME, "User B"))
        auth.session.value = AuthSession.SignedOut
        runCurrent()
        assertEquals(GoalsUiState(), viewModel.state.value)
        viewModel.onEvent("b", GoalsEvent.PreviewDraft)
        assertEquals(GoalsUiState(), viewModel.state.value)
    }

    private fun signedIn(uid: String, email: String? = null) =
        AuthSession.SignedIn(AuthenticatedUser(uid, email))

    private class FakeAuth : AuthRepository {
        override val session = MutableStateFlow<AuthSession>(AuthSession.SignedOut)
        override suspend fun signIn(email: String, password: String) = AuthOperationResult.Success
        override suspend fun register(email: String, password: String) = AuthOperationResult.Success
        override suspend fun resetPassword(email: String) = AuthOperationResult.Success
        override suspend fun signOut() = AuthOperationResult.Success
    }
}
