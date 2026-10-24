package com.pocketpath.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton

enum class UserCollection(val path: String) {
    ACCOUNTS("accounts"),
    CATEGORIES("categories"),
    INCOME_SOURCES("incomeSources"),
    EXPECTED_PAYMENTS("expectedPayments"),
    SCHEDULES("schedules"),
    OCCURRENCES("occurrences"),
    TRANSACTIONS("transactions"),
    GOALS("goals"),
    GOAL_PLANS("goalPlans"),
    BUDGETS("budgets"),
    RATE_SNAPSHOTS("rateSnapshots"),
    CRYPTO_POSITIONS("cryptoPositions"),
}

@Singleton
class UserFirestorePaths @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun forCurrentUser(): UserFirestoreScope {
        val uid = checkNotNull(auth.currentUser?.uid) {
            "An authenticated user is required."
        }

        return UserFirestoreScope(
            userId = uid,
            auth = auth,
            firestore = firestore,
        )
    }
}

/**
 * Bound to one authenticated user.
 *
 * Later repositories must dispose their listeners and state when this
 * session ends. These client checks do not replace Firestore rules.
 */
class UserFirestoreScope internal constructor(
    val userId: String,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    val profile: DocumentReference
        get() {
            requireCurrentSession()
            return firestore.collection("users").document(userId)
        }

    fun collection(collection: UserCollection): CollectionReference {
        return profile.collection(collection.path)
    }

    fun requireCurrentSession() {
        check(auth.currentUser?.uid == userId) {
            "This repository scope no longer belongs to the active session."
        }
    }
}
