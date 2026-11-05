package com.pocketpath.data.local

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptRecoveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun command() = StoredReceiptCommand("payment", 0, com.pocketpath.domain.model.StableIds.expectedPaymentReceipt("payment", 0), "source", "bank", 2_000_000L,
        "2026-11-05T18:29:59.123456Z", "2026-11-05", "Asia/Colombo", "2026-11-05T18:29:59.123456Z")

    private fun record() = StoredReceipt("payment", "Project", "bank", "20000", ReceiptRecoveryPhase.PREPARED, command())

    @Test fun canonicalCommandAndConfirmedOutcomeSurviveFileBackedReopen() = runTest {
        val directory = temporary.newFolder()
        val store = ReceiptRecoveryStore(PrivateReceiptFiles(directory))
        val record = record()
        store.replace("a", null, record)
        val reopened = ReceiptRecoveryStore(PrivateReceiptFiles(directory))
        assertEquals(record, reopened.read("a"))
        assertEquals(record.command, StoredReceiptCommand.from(reopened.read("a")!!.command!!.event(), 0))
        val confirmed = record.copy(phase = ReceiptRecoveryPhase.CONFIRMED, replayed = true)
        reopened.replace("a", record, confirmed)
        assertEquals(confirmed, ReceiptRecoveryStore(PrivateReceiptFiles(directory)).read("a"))
        assertNull(reopened.read("b"))
    }

    @Test fun staleAutosaveCannotReplacePreparedOrConfirmedCommand() = runTest {
        val store = ReceiptRecoveryStore(PrivateReceiptFiles(temporary.newFolder()))
        val draft = StoredReceipt("payment", "Project", "bank", "20000")
        store.replace("a", null, draft)
        store.replace("a", draft, record())
        try { store.replace("a", draft, draft.copy(amountText = "30000")); fail("stale autosave accepted") }
        catch (_: IllegalStateException) { }
        assertEquals(record(), store.read("a"))
    }

    @Test fun corruptOwnerSchemaAndInvalidCanonicalFieldsAreVisible() = runTest {
        val directory = temporary.newFolder()
        val files = PrivateReceiptFiles(directory)
        val store = ReceiptRecoveryStore(files)
        for (bytes in listOf("not json", "{\"schema\":2,\"owner\":\"a\",\"receipt\":null}",
                "{\"schema\":1,\"owner\":\"b\",\"receipt\":null}")) {
            files.write("a", bytes.toByteArray())
            try { store.read("a"); fail("invalid recovery accepted") } catch (_: IllegalArgumentException) { }
        }
        val invalid = listOf(command().copy(originalRevision = 1), command().copy(amountMinor = 0),
            command().copy(accountId = "bad/id"), command().copy(occurredAt = "2026-11-05T18:29:59.123456789Z"),
            command().copy(localDate = "2026-11-06"), command().copy(clientCreatedAt = "invalid"))
        for (candidate in invalid) {
            try { candidate.event(); fail("invalid command accepted") } catch (_: Exception) { }
        }
    }

    @Test fun schemaOneDraftPreparedAndConfirmedRemainReadableWithoutResolutionMetadata() = runTest {
        val files = PrivateReceiptFiles(temporary.newFolder())
        val store = ReceiptRecoveryStore(files)
        for (phase in listOf(ReceiptRecoveryPhase.DRAFT, ReceiptRecoveryPhase.PREPARED, ReceiptRecoveryPhase.CONFIRMED)) {
            val record = if (phase == ReceiptRecoveryPhase.DRAFT) StoredReceipt("payment", "Project", "bank", "20000") else record().copy(phase = phase)
            // Serialize with production codec, then remove the new optional field to reproduce schema 1.
            val isolated = ReceiptRecoveryStore(object : ReceiptFiles {
                var bytes: ByteArray? = null
                override fun read(owner: String) = bytes
                override fun write(owner: String, data: ByteArray) {
                    bytes = data
                    files.write(owner, data.toString(Charsets.UTF_8).replace(",\"resolution\":null", "").toByteArray())
                }
            })
            isolated.replace("a", null, record)
            assertEquals(record, store.read("a"))
        }
    }

    @Test fun ioFailureIsNotAnEmptySuccessfulReadAndCoordinatorBlocksNewWork() = runTest {
        val files = object : ReceiptFiles {
            override fun read(owner: String): ByteArray? = error("read denied")
            override fun write(owner: String, bytes: ByteArray) = error("write denied")
        }
        val c = com.pocketpath.feature.income.ReceiptRecoveryCoordinator("a", ReceiptRecoveryStore(files), { true })
        c.restore()
        assertTrue(c.state.value.storageBlocked)
        assertFalse(c.state.value.restoring)
        assertTrue(c.state.value.error!!.contains("read denied"))
        assertFalse(c.flush())
    }
}
