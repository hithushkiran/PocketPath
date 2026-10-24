package com.pocketpath.domain.repository

import java.time.Instant

enum class DataOrigin { MEMORY_SAMPLE, CACHE, SERVER }
enum class HistoryCompleteness { COMPLETE, INCOMPLETE, NOT_APPLICABLE }
enum class WriteState { LOCAL_SAVED, PENDING, CLOUD_CONFIRMED, REJECTED }
data class ObservationMetadata(
    val origin: DataOrigin,
    val completeness: HistoryCompleteness = HistoryCompleteness.NOT_APPLICABLE,
    val writeState: WriteState? = null,
    val observedAt: Instant? = null,
    val pendingOperationIds: Set<String> = emptySet(),
)
enum class FailureCode { VALIDATION, CONFLICT, NOT_FOUND, UNAUTHENTICATED, PERMISSION_DENIED, NETWORK, REJECTED, INCOMPLETE_HISTORY }
data class RepositoryFailure(val code: FailureCode, val message: String, val operationId: String? = null)

/** Runtime metadata is never an editable field on a financial transaction. Data(emptyList()) is loaded/empty. */
sealed interface Observation<out T> {
    data object Loading : Observation<Nothing>
    data class Data<T>(val value: T, val metadata: ObservationMetadata) : Observation<T>
    data class Failed<T>(val failure: RepositoryFailure, val lastAvailable: Data<T>? = null) : Observation<T>
}

sealed interface CommandResult<out T> {
    data class Accepted<T>(val value: T, val writeState: WriteState, val replayed: Boolean = false) : CommandResult<T>
    data class Rejected(val failure: RepositoryFailure) : CommandResult<Nothing>
}

/** Production implementations bind to authenticated UID and detach/clear on sign-out. No default/test UID. */
interface UserOwnedRepository { val userId: String }
