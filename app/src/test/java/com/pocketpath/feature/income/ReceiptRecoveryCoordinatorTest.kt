package com.pocketpath.feature.income

import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.data.local.*
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReceiptRecoveryCoordinatorTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private val time = Instant.parse("2026-11-05T18:29:59.123456789Z")
    private val payment = ExpectedPayment("payment", "source", "Project", EntryAmount(Money.lkr(6_000_000L)), LocalDate.of(2026, 11, 10))
    private val account = Account("bank", "Bank", AccountType.BANK, FiatCurrency.LKR, true, "opening", LocalDate.of(2026, 1, 1))

    private class Files : ReceiptFiles {
        val data = mutableMapOf<String, ByteArray>()
        var failWrite = false
        override fun read(owner: String) = data[owner]
        override fun write(owner: String, bytes: ByteArray) {
            if (failWrite) error("disk unavailable")
            data[owner] = bytes
        }
    }

    private class Repository(override val userId: String = "a") : IncomeRepository {
        val events = linkedMapOf<String, FinancialTransaction>()
        val revisions = mutableListOf<Long>()
        var inspection: ReceiptInspection = ReceiptInspection.Unavailable(RepositoryFailure(FailureCode.NETWORK, "not configured"))
        var inspectionCalls = 0
        var beforeInspectionReturn: suspend () -> Unit = {}
        override suspend fun inspectExpectedPaymentReceipt(operationId: String, paymentId: String): ReceiptInspection {
            inspectionCalls++
            beforeInspectionReturn()
            return inspection
        }
        var submitOverride: (suspend (FinancialTransaction, Long) -> CommandResult<FinancialTransaction>)? = null
        var loseResponse = false
        var conflict = false
        var beforeReturn: suspend () -> Unit = {}
        override suspend fun recordExpectedPaymentReceipt(event: FinancialTransaction, expectedRevision: Long): CommandResult<FinancialTransaction> {
            revisions += expectedRevision
            submitOverride?.let { return it(event, expectedRevision) }
            val existing = events[event.id]
            if (conflict || existing != null && !FoundationCodec.sameExpectedPaymentReceipt(
                    FoundationCodec.eventData(existing), FoundationCodec.eventData(event))) {
                return CommandResult.Rejected(RepositoryFailure(FailureCode.CONFLICT, "different payload", event.id))
            }
            events.putIfAbsent(event.id, event)
            beforeReturn()
            if (loseResponse) {
                loseResponse = false
                return CommandResult.Rejected(RepositoryFailure(FailureCode.NETWORK, "response lost", event.id))
            }
            return CommandResult.Accepted(existing ?: event, WriteState.CLOUD_CONFIRMED, existing != null)
        }
        override fun observeSources() = flowOf<Observation<List<IncomeSource>>>(Observation.Loading)
        override fun observeExpectedPayments() = flowOf<Observation<List<ExpectedPayment>>>(Observation.Loading)
        override suspend fun saveSource(operationId: String, source: IncomeSource): CommandResult<IncomeSource> = error("not in receipt scope")
        override suspend fun saveExpectedPayment(operationId: String, payment: ExpectedPayment): CommandResult<ExpectedPayment> = error("not in receipt scope")
        override suspend fun cancelExpectedPayment(operationId: String, paymentId: String, expectedRevision: Long): CommandResult<ExpectedPayment> = error("not in receipt scope")
        override suspend fun recordIncome(event: FinancialTransaction): CommandResult<FinancialTransaction> = error("not in receipt scope")
    }

    private fun coordinator(store: ReceiptRecoveryStore, current: () -> Boolean = { true }, at: Instant = time, owner: String = "a") =
        ReceiptRecoveryCoordinator(owner, store, current, Clock.fixed(at, zone))

    private suspend fun draft(c: ReceiptRecoveryCoordinator, amount: String = "20000") {
        c.restore()
        c.begin(payment, account.id)
        c.edit(amount = amount)
    }

    @Test fun lostResponseThenRevisionOneObservationsRetryOriginalCommandExactlyOnce() = runTest {
        val store = ReceiptRecoveryStore(Files())
        val c = coordinator(store)
        val repo = Repository().also { it.loseResponse = true }
        draft(c)
        c.submit(payment, listOf(account), true, repo)
        val original = requireNotNull(c.state.value.receipt?.command)
        assertEquals(StableIds.expectedPaymentReceipt(payment.id, 0), original.operationId)
        assertEquals(2_000_000L, original.amountMinor)
        assertEquals("2026-11-05T18:29:59.123456Z", original.occurredAt)
        val refreshed = payment.copy(receivedAmountMinor = 2_000_000L, lastReceiptOperationId = original.operationId,
            metadata = RecordMetadata(revision = 1))
        val metadata = ObservationMetadata(DataOrigin.SERVER, HistoryCompleteness.COMPLETE)
        val mapped = ExpectedPaymentsStateMapper.map(Observation.Data(listOf(refreshed), metadata),
            Observation.Data(CompleteLedger(repo.events.values.toList()), metadata), LocalDate.MIN, LocalDate.MAX).copy(userId = "a")
        val current = ExpectedPaymentsUiState(userId = "a", selectedReceivingAccountId = account.id, receiptRecovery = c.state.value)
        val updated = ExpectedPaymentsSessionState.preserveTransientState(mapped, current)
        assertEquals(c.state.value, updated.receiptRecovery)
        assertEquals(account.id, updated.selectedReceivingAccountId)
        assertEquals(account.id, ExpectedPaymentsSessionState.withAccounts(updated, Observation.Data(emptyList(), metadata)).selectedReceivingAccountId)
        c.submit(refreshed, emptyList(), false, repo, retry = true)
        assertEquals(listOf(0L, 0L), repo.revisions)
        assertEquals(1, repo.events.size)
        assertEquals(original, c.state.value.receipt?.command)
        assertTrue(c.state.value.receipt!!.replayed)
        assertEquals(ReceiptRecoveryPhase.CONFIRMED, store.read("a")?.phase)
        c.begin(refreshed, account.id)
        assertEquals(original, c.state.value.receipt?.command)
        c.acknowledge()
        assertNull(store.read("a"))
        c.begin(refreshed, account.id)
        assertEquals(ReceiptRecoveryPhase.DRAFT, c.state.value.receipt?.phase)
    }

    @Test fun repeatedTapsDoNotCreateCommandsAndNetworkWaitDoesNotHoldPersistenceMutex() = runTest {
        val c = coordinator(ReceiptRecoveryStore(Files()))
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repo = Repository().also { it.beforeReturn = { entered.complete(Unit); finish.await() } }
        draft(c)
        val first = launch { c.submit(payment, listOf(account), true, repo) }
        entered.await()
        c.submit(payment, listOf(account), true, repo)
        c.submit(null, emptyList(), false, repo, retry = true)
        c.review(repo)
        assertEquals(0, repo.inspectionCalls)
        c.edit(amount = "30000", accountId = "other", selectingAccount = true)
        c.begin(payment, "other")
        assertTrue(withContext(Dispatchers.Default) { withTimeout(5000) { c.flush() } })
        assertEquals(1, repo.revisions.size)
        assertEquals(2_000_000L, c.state.value.receipt?.command?.amountMinor)
        finish.complete(Unit); first.join()
    }

    @Test fun restartAfterMidnightAllowsExplicitReplayWithAbsentPaidOrCancelledPayment() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files)
        val c = coordinator(store); val repo = Repository().also { it.loseResponse = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val original = c.state.value.receipt!!.command
        val restored = coordinator(ReceiptRecoveryStore(files), at = time.plusSeconds(86400))
        restored.restore()
        assertEquals(1, repo.revisions.size) // Restore never submits.
        val cancelled = payment.copy(state = ExpectedPaymentState.CANCELLED, cancelledAt = time,
            receivedAmountMinor = 6_000_000L, metadata = RecordMetadata(revision = 4))
        restored.submit(cancelled, emptyList(), false, repo, retry = true)
        assertEquals(original, restored.state.value.receipt?.command)
        assertTrue(restored.state.value.receipt!!.replayed)
        val absent = coordinator(ReceiptRecoveryStore(files), at = time.plusSeconds(172800))
        absent.restore()
        assertEquals(ReceiptRecoveryPhase.CONFIRMED, absent.state.value.receipt?.phase)
        assertEquals(2, repo.revisions.size)
    }

    @Test fun invalidInputIsRetainedAndConflictNeverRebasesOrCancelsPreparedWork() = runTest {
        val c = coordinator(ReceiptRecoveryStore(Files())); val repo = Repository()
        draft(c, "1.001")
        c.submit(payment, listOf(account), true, repo)
        assertEquals("1.001", c.state.value.receipt?.amountText)
        assertNotNull(c.state.value.error)
        assertTrue(repo.events.isEmpty())
        c.edit(amount = "20000"); repo.conflict = true
        c.submit(payment, listOf(account), true, repo)
        val original = c.state.value.receipt
        c.cancelDraft(); c.acknowledge(); c.begin(payment.copy(metadata = RecordMetadata(revision = 1)), "other")
        c.edit(amount = "30000")
        assertEquals(original, c.state.value.receipt)
        assertTrue(c.state.value.error!!.contains("CONFLICT"))
        c.submit(null, emptyList(), false, repo, retry = true)
        assertEquals(listOf(0L, 0L), repo.revisions)
        assertEquals(original, c.state.value.receipt)
    }

    @Test fun prepareWriteFailureSendsNothingAndRetainsFrozenPayloadForExplicitRetry() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files); val c = coordinator(store); val repo = Repository()
        draft(c); files.failWrite = true
        c.submit(payment, listOf(account), true, repo)
        assertTrue(repo.events.isEmpty())
        val original = c.state.value.receipt!!.command
        assertTrue(c.state.value.storageBlocked)
        files.failWrite = false
        c.submit(null, emptyList(), false, repo, retry = true)
        assertEquals(original, c.state.value.receipt?.command)
        assertEquals(1, repo.events.size)
    }

    @Test fun failedConfirmationWriteSurvivesRestartAndRetriesSameId() = runTest {
        val files = Files(); val c = coordinator(ReceiptRecoveryStore(files)); val repo = Repository()
        draft(c)
        repo.beforeReturn = { files.failWrite = true }
        c.submit(payment, listOf(account), true, repo)
        assertTrue(c.state.value.storageBlocked)
        assertEquals(ReceiptRecoveryPhase.PREPARED, ReceiptRecoveryStore(files).read("a")?.phase)
        c.acknowledge()
        assertNotNull(c.state.value.receipt)
        files.failWrite = false; repo.beforeReturn = {}
        val reopened = coordinator(ReceiptRecoveryStore(files)); reopened.restore()
        reopened.submit(null, emptyList(), false, repo, retry = true)
        assertEquals(1, repo.events.size)
        assertTrue(reopened.state.value.receipt!!.replayed)
    }

    @Test fun cancellationAndUserSwitchKeepOwnerRecoveryWithoutExposingOrSubmittingIt() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files)
        var active = "a"
        val c = coordinator(store, current = { active == "a" })
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repo = Repository().also { it.beforeReturn = { entered.complete(Unit); finish.await() } }
        draft(c)
        val job = launch { c.submit(payment, listOf(account), true, repo) }
        entered.await(); active = "b"; job.cancelAndJoin()
        assertEquals(ReceiptRecoveryPhase.PREPARED, store.read("a")?.phase)
        val b = coordinator(store, current = { active == "b" }, owner = "b"); b.restore()
        assertNull(b.state.value.receipt)
        c.submit(null, emptyList(), false, repo, retry = true)
        assertEquals(1, repo.revisions.size)
        assertNull(ExpectedPaymentsSessionState.signedOut().receiptRecovery.receipt)
        active = "a"; repo.beforeReturn = {}
        val owner = coordinator(store, current = { active == "a" }); owner.restore()
        owner.submit(null, emptyList(), false, repo, retry = true)
        assertTrue(owner.state.value.receipt!!.replayed)
        assertEquals(1, repo.events.size)
    }

    @Test fun competingImmutableOperationResolvesDurablyWithoutPostingAndNewBeginIsSeparate() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files); val c = coordinator(store)
        val repo = Repository().also { it.conflict = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val retained = c.state.value.receipt!!.command!!
        val competing = retained.copy(amountMinor = 3_000_000L).event()
        repo.events[competing.id] = competing
        val serverPayment = payment.copy(receivedAmountMinor = 3_000_000L, lastReceiptOperationId = competing.id, metadata = RecordMetadata(revision = 1))
        repo.inspection = ReceiptInspection.Authoritative("a", retained.operationId, payment.id, competing, serverPayment)
        c.review(repo)
        assertEquals(ReceiptRecoveryPhase.RESOLVED, c.state.value.receipt?.phase)
        assertEquals(ReceiptResolutionKind.DIFFERENT_OPERATION, c.state.value.receipt?.resolution?.kind)
        assertTrue(c.state.value.receipt!!.resolution!!.reason.contains("not confirmation"))
        assertEquals(retained, c.state.value.receipt?.command)
        assertEquals(1, repo.revisions.size)
        assertEquals(1, repo.events.size)
        val restarted = coordinator(ReceiptRecoveryStore(files)); restarted.restore()
        assertEquals(c.state.value.receipt, restarted.state.value.receipt)
        restarted.begin(serverPayment, account.id)
        assertEquals(ReceiptRecoveryPhase.RESOLVED, restarted.state.value.receipt?.phase)
        restarted.acknowledge()
        assertNull(restarted.state.value.receipt)
        assertEquals(1, repo.events.size)
        restarted.begin(serverPayment, account.id)
        assertEquals(ReceiptRecoveryPhase.DRAFT, restarted.state.value.receipt?.phase)
        assertEquals(1, repo.revisions.size)
    }

    @Test fun identicalAuthoritativeReviewRecoversConfirmationWithoutSubmission() = runTest {
        val c = coordinator(ReceiptRecoveryStore(Files())); val repo = Repository().also { it.loseResponse = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val command = c.state.value.receipt!!.command!!
        val updated = payment.copy(receivedAmountMinor = command.amountMinor, lastReceiptOperationId = command.operationId, metadata = RecordMetadata(revision = 1))
        repo.inspection = ReceiptInspection.Authoritative("a", command.operationId, payment.id, repo.events[command.operationId], updated)
        c.review(repo)
        assertEquals(ReceiptRecoveryPhase.CONFIRMED, c.state.value.receipt?.phase)
        assertEquals(ReceiptResolutionKind.IDENTICAL, c.state.value.receipt?.resolution?.kind)
        assertTrue(c.state.value.receipt!!.replayed)
        assertEquals(1, repo.revisions.size)
        assertEquals(1, repo.events.size)
    }

    @Test fun absenceOnlyResolvesWithMonotonicRevisionOrTerminalCancellation() = runTest {
        for (cancelled in listOf(false, true)) {
            val c = coordinator(ReceiptRecoveryStore(Files())); val repo = Repository().also { it.conflict = true }
            draft(c); c.submit(payment, listOf(account), true, repo)
            val command = c.state.value.receipt!!.command!!
            val changed = payment.copy(metadata = RecordMetadata(revision = 1),
                state = if (cancelled) ExpectedPaymentState.CANCELLED else ExpectedPaymentState.ACTIVE,
                cancelledAt = if (cancelled) time else null)
            repo.inspection = ReceiptInspection.Authoritative("a", command.operationId, payment.id, null, changed)
            c.review(repo)
            assertEquals(ReceiptResolutionKind.PERMANENT_REJECTION, c.state.value.receipt?.resolution?.kind)
            assertTrue(c.state.value.receipt!!.resolution!!.reason.contains("not a receipt confirmation"))
            assertTrue(repo.events.isEmpty())
            assertEquals(1, repo.revisions.size)
            c.acknowledge(); assertNull(c.state.value.receipt)
        }
    }

    @Test fun cancelledAwaitCannotLaterCommitAfterAuthoritativePermanentResolution() = runTest {
        val files = Files(); val c = coordinator(ReceiptRecoveryStore(files)); val repo = Repository()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val result = CompletableDeferred<CommandResult<FinancialTransaction>>()
        val advanced = payment.copy(metadata = RecordMetadata(revision = 1))
        repo.submitOverride = { event, revision ->
            backgroundScope.launch {
                started.complete(Unit); release.await()
                // Models a Firestore transaction conflict/re-read after another revision wins.
                val decision = com.pocketpath.domain.calculation.allocateExpectedPaymentReceipt(
                    advanced, revision, payment.incomeSourceId, Money.lkr(2_000_000L))
                assertTrue(decision is com.pocketpath.domain.calculation.ExpectedPaymentReceiptAllocation.StaleRevision)
                result.complete(CommandResult.Rejected(RepositoryFailure(FailureCode.CONFLICT, "stale transaction", event.id)))
            }
            result.await()
        }
        draft(c)
        val submission = launch { c.submit(payment, listOf(account), true, repo) }
        started.await(); submission.cancelAndJoin() // underlying server task is still pending
        val restarted = coordinator(ReceiptRecoveryStore(files)); restarted.restore()
        val command = restarted.state.value.receipt!!.command!!
        repo.inspection = ReceiptInspection.Authoritative("a", command.operationId, payment.id, null, advanced)
        restarted.review(repo); restarted.acknowledge()
        assertNull(restarted.state.value.receipt)
        release.complete(Unit); result.await()
        assertTrue(repo.events.isEmpty())
        assertEquals(1, repo.revisions.size)
    }

    @Test fun unchangedCacheFailedOrInconsistentInspectionCannotResolve() = runTest {
        val c = coordinator(ReceiptRecoveryStore(Files())); val repo = Repository().also { it.conflict = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val original = c.state.value.receipt!!; val command = original.command!!
        val evidence = listOf(
            ReceiptInspection.Authoritative("a", command.operationId, payment.id, null, payment),
            ReceiptInspection.Authoritative("a", command.operationId, payment.id, null, null),
            ReceiptInspection.Authoritative("a", command.operationId, payment.id, command.event(), payment),
            ReceiptInspection.Authoritative("other-user", command.operationId, payment.id, null, payment.copy(metadata = RecordMetadata(revision = 1))),
            ReceiptInspection.Unavailable(RepositoryFailure(FailureCode.NETWORK, "cache-only absence")),
            ReceiptInspection.Unavailable(RepositoryFailure(FailureCode.PERMISSION_DENIED, "denied")),
        )
        for (inspection in evidence) {
            repo.inspection = inspection; c.review(repo); c.acknowledge()
            assertEquals(original, c.state.value.receipt)
            assertNotNull(c.state.value.error)
        }
        assertEquals(1, repo.revisions.size)
    }

    @Test fun reviewCannotRaceSubmissionOrResolveFromStaleSession() = runTest {
        val files = Files(); var current = true
        val c = coordinator(ReceiptRecoveryStore(files), current = { current })
        val repo = Repository().also { it.loseResponse = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val command = c.state.value.receipt!!.command!!
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        repo.beforeInspectionReturn = { entered.complete(Unit); finish.await() }
        repo.inspection = ReceiptInspection.Authoritative("a", command.operationId, payment.id,
            command.copy(amountMinor = 3_000_000L).event(), payment.copy(metadata = RecordMetadata(revision = 1)))
        val review = launch { c.review(repo) }; entered.await()
        c.submit(null, emptyList(), false, repo, retry = true); c.acknowledge()
        assertEquals(1, repo.revisions.size)
        current = false; finish.complete(Unit); review.join()
        assertEquals(ReceiptRecoveryPhase.PREPARED, ReceiptRecoveryStore(files).read("a")?.phase)
        assertNull(ReceiptRecoveryStore(files).read("b"))
        assertEquals(ReceiptRecoveryPhase.PREPARED, c.state.value.receipt?.phase)
    }

    @Test fun failedResolutionAndAcknowledgmentWritesKeepCommandThroughRestart() = runTest {
        val files = Files(); val store = ReceiptRecoveryStore(files); val c = coordinator(store)
        val repo = Repository().also { it.conflict = true }
        draft(c); c.submit(payment, listOf(account), true, repo)
        val command = c.state.value.receipt!!.command!!
        repo.inspection = ReceiptInspection.Authoritative("a", command.operationId, payment.id, null,
            payment.copy(metadata = RecordMetadata(revision = 1)))
        repo.beforeInspectionReturn = { files.failWrite = true }
        c.review(repo); assertTrue(c.state.value.storageBlocked)
        assertEquals(ReceiptRecoveryPhase.PREPARED, store.read("a")?.phase)
        c.acknowledge(); assertNotNull(c.state.value.receipt)
        files.failWrite = false; repo.beforeInspectionReturn = {}
        val restarted = coordinator(ReceiptRecoveryStore(files)); restarted.restore()
        assertEquals(command, restarted.state.value.receipt?.command)
        restarted.review(repo)
        assertEquals(ReceiptRecoveryPhase.RESOLVED, restarted.state.value.receipt?.phase)
        files.failWrite = true; restarted.acknowledge()
        assertNotNull(restarted.state.value.receipt)
        assertTrue(restarted.state.value.storageBlocked)
        assertEquals(ReceiptRecoveryPhase.RESOLVED, store.read("a")?.phase)
        restarted.begin(payment, account.id); assertEquals(command, restarted.state.value.receipt?.command)
        files.failWrite = false; restarted.submit(null, emptyList(), false, repo, retry = true)
        restarted.acknowledge(); assertNull(restarted.state.value.receipt)
        assertTrue(repo.events.isEmpty())
    }

    @Test fun replayIdentityIncludesAccountAmountFinancialDateZoneAndClientTime() {
        val event = ExpectedPaymentReceiptFactory.create(StableIds.expectedPaymentReceipt(payment.id, 0), payment,
            account.id, "20000", listOf(account), time, zone)
        val stored = StoredReceiptCommand.from(event, 0)
        val original = FoundationCodec.eventData(stored.event())
        assertTrue(FoundationCodec.sameExpectedPaymentReceipt(original, original + ("createdAt" to null)))
        val changed = listOf(stored.copy(accountId = "other"), stored.copy(amountMinor = 2_000_001L),
            stored.copy(occurredAt = "2026-11-06T18:29:59.123456Z", localDate = "2026-11-06"),
            stored.copy(zone = "UTC"), stored.copy(clientCreatedAt = "2026-11-05T18:29:58.123456Z"))
        changed.forEach { assertFalse(FoundationCodec.sameExpectedPaymentReceipt(original, FoundationCodec.eventData(it.event()))) }
    }
}
