package com.pocketpath.feature.auth

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pocketpath.R
import com.pocketpath.domain.repository.AuthError
import com.pocketpath.domain.repository.AuthOperationResult
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.AuthenticatedUser
import kotlinx.coroutines.CompletableDeferred
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
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repository: FakeAuthRepository
    private lateinit var viewModel: AuthViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAuthRepository()
        viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                modelClass.cast(AuthViewModel(repository))!!
        })[AuthViewModel::class.java]
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun repositorySessionChangesReachExposedState() = runTest(dispatcher) {
        assertEquals(AuthSession.Checking, viewModel.session.value)
        repository.session.value = AuthSession.SignedOut
        runCurrent()
        assertEquals(AuthSession.SignedOut, viewModel.session.value)
        val signedIn = AuthSession.SignedIn(AuthenticatedUser("fake-user", "person@example.test"))
        repository.session.value = signedIn
        runCurrent()
        assertEquals(signedIn, viewModel.session.value)
    }

    @Test fun repeatedSubmissionsWhileBusyIssueOneRequest() = runTest(dispatcher) {
        val response = CompletableDeferred<AuthOperationResult>()
        repository.signInResult = { response.await() }
        viewModel.signIn("person@example.test", " password ")
        assertTrue(viewModel.actionState.value.busy)
        viewModel.signIn("person@example.test", " password ")
        viewModel.register("person@example.test", "password", "password")
        viewModel.resetPassword("person@example.test")
        viewModel.signOut()
        runCurrent()
        assertEquals(1, repository.signInCalls)
        assertEquals(0, repository.registerCalls + repository.resetCalls + repository.signOutCalls)
        assertTrue(viewModel.actionState.value.busy)
        response.complete(AuthOperationResult.Success)
        runCurrent()
        assertFalse(viewModel.actionState.value.busy)
    }

    @Test fun failedRequestReleasesBusyAndAllowsRetry() = runTest(dispatcher) {
        repository.signInResult = { AuthOperationResult.Failure(AuthError.NETWORK) }
        viewModel.signIn("person@example.test", "password")
        runCurrent()
        assertFalse(viewModel.actionState.value.busy)
        assertTrue(viewModel.actionState.value.isError)
        assertEquals(R.string.auth_error_network, viewModel.actionState.value.messageRes)
        repository.signInResult = { AuthOperationResult.Success }
        viewModel.signIn("person@example.test", "password")
        assertTrue(viewModel.actionState.value.busy)
        runCurrent()
        assertEquals(2, repository.signInCalls)
        assertEquals(AuthActionState(), viewModel.actionState.value)
    }

    @Test fun unexpectedFailureAlsoReleasesBusyAndAllowsRetry() = runTest(dispatcher) {
        repository.signInResult = { throw IllegalStateException("Synthetic failure") }
        viewModel.signIn("person@example.test", "password")
        runCurrent()
        assertFalse(viewModel.actionState.value.busy)
        assertEquals(R.string.auth_error_generic, viewModel.actionState.value.messageRes)
        repository.signInResult = { AuthOperationResult.Success }
        viewModel.signIn("person@example.test", "password")
        runCurrent()
        assertEquals(2, repository.signInCalls)
        assertFalse(viewModel.actionState.value.isError)
    }

    @Test fun passwordMismatchDoesNotRegister() = runTest(dispatcher) {
        viewModel.register("person@example.test", "password", "different")
        runCurrent()
        assertEquals(0, repository.registerCalls)
        assertFalse(viewModel.actionState.value.busy)
        assertEquals(R.string.auth_error_confirmation, viewModel.actionState.value.messageRes)
    }

    @Test fun signOutCallsRepositoryAndSignedOutReachesUiState() = runTest(dispatcher) {
        repository.session.value = AuthSession.SignedIn(AuthenticatedUser("fake-user", null))
        runCurrent()
        assertTrue(viewModel.session.value is AuthSession.SignedIn)
        repository.signOutResult = {
            repository.session.value = AuthSession.SignedOut
            AuthOperationResult.Success
        }
        viewModel.signOut()
        assertTrue(viewModel.actionState.value.busy)
        runCurrent()
        assertEquals(1, repository.signOutCalls)
        assertEquals(AuthSession.SignedOut, viewModel.session.value)
        assertFalse(viewModel.actionState.value.busy)
    }

    @Test fun resetUsesGenericSuccessAndSignInPreservesPasswordWhitespace() = runTest(dispatcher) {
        viewModel.resetPassword("person@example.test")
        runCurrent()
        assertEquals(1, repository.resetCalls)
        assertEquals(R.string.auth_reset_sent, viewModel.actionState.value.messageRes)
        assertFalse(viewModel.actionState.value.isError)
        viewModel.signIn(" person@example.test ", " password ")
        runCurrent()
        assertEquals("person@example.test", repository.lastEmail)
        assertEquals(" password ", repository.lastPassword)
    }

    private class FakeAuthRepository : AuthRepository {
        override val session = MutableStateFlow<AuthSession>(AuthSession.Checking)
        var signInCalls = 0
        var registerCalls = 0
        var resetCalls = 0
        var signOutCalls = 0
        var lastEmail: String? = null
        var lastPassword: String? = null
        var signInResult: suspend () -> AuthOperationResult = { AuthOperationResult.Success }
        var signOutResult: suspend () -> AuthOperationResult = { AuthOperationResult.Success }

        override suspend fun signIn(email: String, password: String): AuthOperationResult {
            signInCalls++
            lastEmail = email
            lastPassword = password
            return signInResult()
        }
        override suspend fun register(email: String, password: String): AuthOperationResult {
            registerCalls++
            return AuthOperationResult.Success
        }
        override suspend fun resetPassword(email: String): AuthOperationResult {
            resetCalls++
            return AuthOperationResult.Success
        }
        override suspend fun signOut(): AuthOperationResult {
            signOutCalls++
            return signOutResult()
        }
    }
}
