package com.pocketpath.feature.income

import com.pocketpath.data.local.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReceiptSignOutGateTest {
    private class Files : ReceiptFiles {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        override fun read(owner: String): ByteArray? {
            if (failRead) error("read denied")
            return bytes
        }
        override fun write(owner: String, bytes: ByteArray) {
            writes++
            if (failWrite) error("write denied")
            this.bytes = bytes
        }
    }

    private val payment = ExpectedPayment("payment", "source", "Project", EntryAmount(Money.lkr(6_000_000L)), LocalDate.of(2026, 11, 10))

    @Test fun knownDurableEmptySignsOutWithoutCreatingOrWritingRecoveryFile() = runTest {
        val files = Files(); val c = ReceiptRecoveryCoordinator("a", ReceiptRecoveryStore(files), { true }); c.restore()
        var financeFlushes = 0
        val gate = ReceiptSignOutGate({ true }, c::flush, { c.state.value.error })
        gate.overrideStorageFailure(); assertEquals(0, financeFlushes)
        files.failWrite = true
        gate.request { financeFlushes++ }
        assertEquals(1, financeFlushes)
        assertEquals(0, files.writes)
        assertNull(files.bytes)
        assertNull(gate.state.value.failure)
    }

    @Test fun durablePreparedCommandIsReadyWithoutExtraWriteOrNetworkWait() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files)
        val command = StoredReceiptCommand("payment", 0, StableIds.expectedPaymentReceipt("payment", 0), "source", "bank", 2_000_000L,
            "2026-11-05T06:00:00Z", "2026-11-05", "Asia/Colombo", "2026-11-05T06:00:00Z")
        store.replace("a", null, StoredReceipt("payment", "Project", "bank", "20000", ReceiptRecoveryPhase.PREPARED, command))
        val c = ReceiptRecoveryCoordinator("a", store, { true }); c.restore()
        val original = files.bytes!!.copyOf(); val writes = files.writes
        files.failWrite = true
        var financeFlushes = 0
        val gate = ReceiptSignOutGate({ true }, c::flush, { c.state.value.error })
        gate.request { financeFlushes++ }
        assertEquals(1, financeFlushes)
        assertEquals(writes, files.writes)
        assertArrayEquals(original, files.bytes)
    }

    @Test fun corruptRestoreFailureOffersExplicitOverrideWithoutTouchingRecoveryBytes() = runTest {
        val files = Files().also { it.bytes = "corrupt existing recovery".toByteArray() }
        val original = files.bytes!!.copyOf()
        val c = ReceiptRecoveryCoordinator("a", ReceiptRecoveryStore(files), { true }); c.restore()
        var financeFlushes = 0
        val gate = ReceiptSignOutGate({ true }, c::flush, { c.state.value.error })
        gate.request { financeFlushes++ }
        assertNotNull(gate.state.value.failure)
        assertEquals(0, financeFlushes)
        gate.retry(); assertEquals(0, financeFlushes)
        assertArrayEquals(original, files.bytes)
        gate.overrideStorageFailure()
        assertEquals(1, financeFlushes) // Exactly the production continuation into P01 finance flush.
        assertEquals(0, files.writes)
        assertArrayEquals(original, files.bytes)
        gate.overrideStorageFailure(); assertEquals(1, financeFlushes)
    }

    @Test fun failedFlushCanRetryStayOrOverrideWithoutPromisingPersistence() = runTest {
        val files = Files(); val c = ReceiptRecoveryCoordinator("a", ReceiptRecoveryStore(files), { true })
        c.restore(); c.begin(payment, "bank")
        val original = files.bytes!!.copyOf()
        files.failWrite = true; c.edit(amount = "20000")
        var financeFlushes = 0
        val gate = ReceiptSignOutGate({ true }, c::flush, { c.state.value.error })
        gate.request { financeFlushes++ }
        assertNotNull(gate.state.value.failure); assertEquals(0, financeFlushes)
        gate.stay(); gate.overrideStorageFailure(); assertEquals(0, financeFlushes)
        gate.request { financeFlushes++ }
        val writesBeforeOverride = files.writes
        gate.overrideStorageFailure()
        assertEquals(1, financeFlushes)
        assertEquals(writesBeforeOverride, files.writes)
        assertArrayEquals(original, files.bytes)
        assertEquals("20000", c.state.value.receipt?.amountText)
    }

    @Test fun retrySuccessfulFlushUsesNormalFinanceContinuationAndDurableInput() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files)
        val c = ReceiptRecoveryCoordinator("a", store, { true }); c.restore(); c.begin(payment, "bank")
        files.failWrite = true; c.edit(amount = "20000")
        var financeFlushes = 0
        val gate = ReceiptSignOutGate({ true }, c::flush, { c.state.value.error })
        gate.request { financeFlushes++ }; assertEquals(0, financeFlushes)
        files.failWrite = false; gate.retry()
        assertEquals(1, financeFlushes)
        assertEquals("20000", store.read("a")?.amountText)
        assertNull(gate.state.value.failure)
    }

    @Test fun userChangeInvalidatesBothLateSuccessAndFailureOverrideContinuations() = runTest {
        var current = true; var financeFlushes = 0
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Boolean>()
        val gate = ReceiptSignOutGate({ current }, { entered.complete(Unit); finish.await() }, { "failed" })
        val request = launch { gate.request { financeFlushes++ } }
        entered.await(); current = false; finish.complete(true); request.join()
        assertEquals(0, financeFlushes)
        val failed = ReceiptSignOutGate({ current }, { false }, { "read failed" })
        current = true; failed.request { financeFlushes++ }
        assertNotNull(failed.state.value.failure)
        current = false; failed.overrideStorageFailure(); failed.retry()
        assertEquals(0, financeFlushes)
    }
}
