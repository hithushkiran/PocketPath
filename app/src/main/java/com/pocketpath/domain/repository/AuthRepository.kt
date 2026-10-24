package com.pocketpath.domain.repository

import kotlinx.coroutines.flow.Flow

data class AuthenticatedUser(
    val uid: String,
    val email: String?,
)

sealed interface AuthSession {
    data object Checking : AuthSession
    data object SignedOut : AuthSession
    data class SignedIn(val user: AuthenticatedUser) : AuthSession
}

enum class AuthError {
    INVALID_EMAIL,
    INVALID_CREDENTIALS,
    WEAK_PASSWORD,
    REGISTRATION_REJECTED,
    NETWORK,
    TOO_MANY_REQUESTS,
    UNAVAILABLE,
    UNKNOWN,
}

sealed interface AuthOperationResult {
    data object Success : AuthOperationResult
    data class Failure(val error: AuthError) : AuthOperationResult
}

interface AuthRepository {
    val session: Flow<AuthSession>

    suspend fun signIn(
        email: String,
        password: String,
    ): AuthOperationResult

    suspend fun register(
        email: String,
        password: String,
    ): AuthOperationResult

    suspend fun resetPassword(email: String): AuthOperationResult

    suspend fun signOut(): AuthOperationResult
}
