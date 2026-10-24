package com.pocketpath.data.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.pocketpath.domain.repository.AuthError
import com.pocketpath.domain.repository.AuthOperationResult
import com.pocketpath.domain.repository.AuthRepository
import com.pocketpath.domain.repository.AuthSession
import com.pocketpath.domain.repository.AuthenticatedUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : AuthRepository {

    override val session: Flow<AuthSession> = callbackFlow<AuthSession> {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser

            val nextSession = if (user == null) {
                AuthSession.SignedOut
            } else {
                AuthSession.SignedIn(
                    AuthenticatedUser(
                        uid = user.uid,
                        email = user.email,
                    ),
                )
            }

            trySend(nextSession)
        }

        firebaseAuth.addAuthStateListener(listener)

        awaitClose {
            firebaseAuth.removeAuthStateListener(listener)
        }
    }.distinctUntilChanged()

    override suspend fun signIn(
        email: String,
        password: String,
    ): AuthOperationResult = request {
        firebaseAuth.signInWithEmailAndPassword(
            email.trim(),
            password,
        ).await()
    }

    override suspend fun register(
        email: String,
        password: String,
    ): AuthOperationResult = request {
        firebaseAuth.createUserWithEmailAndPassword(
            email.trim(),
            password,
        ).await()
    }

    override suspend fun resetPassword(
        email: String,
    ): AuthOperationResult = request {
        try {
            firebaseAuth.sendPasswordResetEmail(email.trim()).await()
        } catch (exception: FirebaseAuthInvalidUserException) {
            // Preserve the same public response for an unregistered email.
            if (exception.errorCode != "ERROR_USER_NOT_FOUND") {
                throw exception
            }
        }
    }

    override suspend fun signOut(): AuthOperationResult = request {
        firebaseAuth.signOut()
    }

    private suspend fun request(
        operation: suspend () -> Unit,
    ): AuthOperationResult {
        return try {
            operation()
            AuthOperationResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            AuthOperationResult.Failure(exception.toAuthError())
        }
    }

    private fun Exception.toAuthError(): AuthError = when (this) {
        is FirebaseNetworkException -> AuthError.NETWORK
        is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
        is FirebaseAuthWeakPasswordException -> AuthError.WEAK_PASSWORD
        is FirebaseAuthUserCollisionException -> AuthError.REGISTRATION_REJECTED

        is FirebaseAuthInvalidCredentialsException -> {
            if (errorCode == "ERROR_INVALID_EMAIL") {
                AuthError.INVALID_EMAIL
            } else {
                AuthError.INVALID_CREDENTIALS
            }
        }

        is FirebaseAuthInvalidUserException -> AuthError.INVALID_CREDENTIALS
        is FirebaseAuthException -> AuthError.UNAVAILABLE
        else -> AuthError.UNKNOWN
    }
}
