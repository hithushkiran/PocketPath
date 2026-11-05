package com.pocketpath.feature.income

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceiptSignOutState(val checking: Boolean = false, val failure: String? = null)

/** Receipt-only barrier. Every successful continuation still enters the existing P01 finance flush. */
class ReceiptSignOutGate(
    private val isCurrent: () -> Boolean,
    private val flush: suspend () -> Boolean,
    private val storageError: () -> String?,
) {
    private val _state = MutableStateFlow(ReceiptSignOutState())
    val state = _state.asStateFlow()
    private var pending: (() -> Unit)? = null

    suspend fun request(continueToFinanceFlush: () -> Unit) {
        if (!isCurrent() || _state.value.checking) return
        pending = continueToFinanceFlush
        _state.value = ReceiptSignOutState(checking = true)
        val ready = try {
            flush()
        } catch (cancelled: CancellationException) {
            _state.value = ReceiptSignOutState(failure = "Receipt persistence was interrupted.")
            throw cancelled
        } catch (error: Exception) {
            _state.value = ReceiptSignOutState(failure = error.message ?: "Receipt persistence failed.")
            false
        }
        if (!isCurrent()) {
            pending = null
            _state.value = ReceiptSignOutState()
            return
        }
        if (ready) {
            val continuation = pending
            pending = null
            _state.value = ReceiptSignOutState()
            continuation?.invoke()
        } else {
            _state.value = ReceiptSignOutState(failure = _state.value.failure ?: storageError() ?:
                "Receipt storage could not be read or flushed. Persistence is not confirmed.")
        }
    }

    suspend fun retry() {
        val continuation = pending ?: return
        request(continuation)
    }

    fun stay() {
        pending = null
        _state.value = ReceiptSignOutState()
    }

    /** Explicit failure acknowledgment only. No storage read, write, deletion or silent bypass. */
    fun overrideStorageFailure() {
        if (!isCurrent() || _state.value.checking || _state.value.failure == null) return
        val continuation = pending ?: return
        pending = null
        _state.value = ReceiptSignOutState()
        continuation()
    }
}
