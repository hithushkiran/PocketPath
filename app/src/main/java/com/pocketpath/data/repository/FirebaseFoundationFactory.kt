package com.pocketpath.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pocketpath.data.firebase.UserFirestorePaths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped factory, with a separate repository for each auth session.
 * Uses the existing FirebaseModule and UserFirestorePaths bindings.
 */
@Singleton
class FirebaseFoundationFactory @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val paths: UserFirestorePaths,
    private val local: com.pocketpath.data.local.FinanceLocalStore,
    private val offlineWriter: OfflineExpenseWriter,
) {
    private val lock = Any()
    private var repository: FirebaseFoundationRepository? = null

    init {
        auth.addAuthStateListener { currentAuth ->
            synchronized(lock) {
                if (repository?.userId != currentAuth.currentUser?.uid) {
                    repository?.closeSession()
                    repository = null
                }
            }
        }
    }

    fun current(): FirebaseFoundationRepository = synchronized(lock) {
        val scope = paths.forCurrentUser()
        val existing = repository

        if (existing != null && existing.userId == scope.userId) {
            existing
        } else {
            existing?.closeSession()

            FirebaseFoundationRepository(
                owned = scope,
                auth = auth,
                firestore = firestore,
                local = local,
                offlineWriter = offlineWriter,
            ).also {
                repository = it
            }
        }
    }
}
