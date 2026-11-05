package com.pocketpath.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.data.firebase.UserFirestorePaths
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.CommandResult
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.ReceiptInspection
import com.pocketpath.domain.repository.WriteState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production repository/SDK execution; every named client is emulator-only. */
@RunWith(AndroidJUnit4::class)
class FirebaseIncomeRepositoryEmulatorTest {
    companion object {
        private const val PROJECT = "demo-pocketpath-pr3"
        private const val AVD_HOST = "10.0.2.2"
        private const val AUTH_PORT = 9099
        private const val FIRESTORE_PORT = 8080
        private val createdApps = mutableListOf<FirebaseApp>()

        @JvmStatic @AfterClass fun deleteNamedAppsAfterClass() {
            // Auth's emulator registry retains API-key callbacks across tests. Keep named apps
            // alive until all four tests finish; deleting them between tests breaks useEmulator.
            createdApps.asReversed().forEach { it.delete() }
            createdApps.clear()
        }
    }

    private data class Client(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val factory: FirebaseIncomeRepositoryFactory,
    ) {
        val repository: FirebaseIncomeRepository get() = factory.current()
    }

    private val clients = mutableListOf<Client>()
    private lateinit var first: Client
    private lateinit var second: Client
    private lateinit var uid: String
    private lateinit var emulatorHost: String
    private val zone = ZoneId.of("Asia/Colombo")
    private val instant = Instant.parse("2026-11-05T06:00:00.123456Z")
    private val payment = ExpectedPayment(
        id = "payment", incomeSourceId = "source", projectLabel = "Local Android receipt test",
        amountDue = EntryAmount(Money.lkr(6_000_000L)), dueDate = LocalDate.of(2026, 11, 15),
    )

    @Before fun setUp() = runBlocking {
        // The host runner checks local services and the selected phone/AVD transport.
        assertEquals("Run via the local emulator host command in tools/android-repository/README.md",
            PROJECT, InstrumentationRegistry.getArguments().getString("pr3DemoProject"))
        emulatorHost = InstrumentationRegistry.getArguments().getString("pr3EmulatorHost") ?: AVD_HOST
        require(emulatorHost in setOf(AVD_HOST, "127.0.0.1")) { "Only local emulator hosts are permitted." }
        withTimeout(60_000) {
            first = newClient("first")
            val email = "pr3-${UUID.randomUUID()}@example.test"
            val password = "Local-emulator-only-123!"
            uid = requireNotNull(first.auth.createUserWithEmailAndPassword(email, password).await().user).uid
            second = newClient("second")
            val secondUid = requireNotNull(second.auth.signInWithEmailAndPassword(email, password).await().user).uid
            assertEquals(uid, secondUid)
            seedAccount(first)
            val source = IncomeSource("source", "Local salary", IncomeSourceKind.SALARY,
                defaultAccountId = "bank", defaultCurrency = FiatCurrency.LKR)
            accepted(first.repository.saveSource(source.id, source))
            accepted(first.repository.saveExpectedPayment(payment.id, payment))
            assertEquals(0L, serverPayment(first).metadata.revision)
        }
    }

    private fun newClient(label: String): Client {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT)
            .setApplicationId("1:1234567890:android:0123456789abcdef")
            .setApiKey("local-emulator-only-api-key")
            .build()
        val app = FirebaseApp.initializeApp(context, options, "pr3-$label-${UUID.randomUUID()}")
        createdApps += app
        assertEquals(PROJECT, app.options.projectId)
        // Configure each named service before creating users or issuing any Firestore request.
        // No default FirebaseApp, production Auth binding or production Firestore is accessed.
        val auth = FirebaseAuth.getInstance(app)
        auth.useEmulator(emulatorHost, AUTH_PORT)
        val firestore = FirebaseFirestore.getInstance(app)
        @Suppress("DEPRECATION")
        val settings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        firestore.firestoreSettings = settings
        firestore.useEmulator(emulatorHost, FIRESTORE_PORT)
        return Client(app, auth, firestore,
            FirebaseIncomeRepositoryFactory(auth, firestore, UserFirestorePaths(auth, firestore)))
            .also(clients::add)
    }

    private fun user(client: Client) = client.firestore.collection("users").document(uid)
    private fun paymentRef(client: Client) = user(client).collection("expectedPayments").document(payment.id)
    private fun receiptRef(client: Client, id: String) = user(client).collection("transactions").document(id)

    private suspend fun seedAccount(client: Client) {
        val time = FinancialTime(instant, instant.atZone(zone).toLocalDate(), zone)
        val account = Account("bank", "Local bank", AccountType.BANK, FiatCurrency.LKR, true,
            "opening-bank", time.occurredLocalDate)
        val opening = FinancialTransaction(
            operationId = account.openingTransactionId, kind = TransactionKind.OPENING,
            status = TransactionStatus.POSTED, financialTime = time, clientCreatedAt = instant,
            movements = listOf(AccountMovement(account.id, NativeAmount.Fiat(Money.lkr(1_000_000L)))),
        )
        val batch = client.firestore.batch()
        batch.set(user(client), FoundationCodec.defaultProfileData())
        batch.set(user(client).collection("accounts").document(account.id), FoundationCodec.accountData(account))
        batch.set(receiptRef(client, opening.id), FoundationCodec.eventData(opening))
        batch.commit().await()
    }

    private fun receipt(amount: Long = 2_000_000L, revision: Long = 0): FinancialTransaction {
        val money = Money.lkr(amount)
        return FinancialTransaction(
            operationId = StableIds.expectedPaymentReceipt(payment.id, revision),
            kind = TransactionKind.INCOME, status = TransactionStatus.POSTED,
            financialTime = FinancialTime(instant, instant.atZone(zone).toLocalDate(), zone),
            clientCreatedAt = instant,
            movements = listOf(AccountMovement("bank", NativeAmount.Fiat(money))),
            components = listOf(ReportComponent(ComponentNature.EARNING, KnownLkrAmount.Known(money), incomeSourceId = payment.incomeSourceId)),
            expectedPaymentId = payment.id,
        )
    }

    private fun <T> accepted(result: CommandResult<T>): CommandResult.Accepted<T> {
        assertTrue("Expected acceptance, got $result", result is CommandResult.Accepted)
        return result as CommandResult.Accepted<T>
    }

    private fun rejected(result: CommandResult<*>, code: FailureCode) {
        assertTrue("Expected $code, got $result", result is CommandResult.Rejected)
        assertEquals(code, (result as CommandResult.Rejected).failure.code)
    }

    private suspend fun serverPayment(client: Client): ExpectedPayment {
        val snapshot = paymentRef(client).get(Source.SERVER).await()
        assertFalse(snapshot.metadata.isFromCache)
        assertFalse(snapshot.metadata.hasPendingWrites())
        return FoundationCodec.expectedPayment(snapshot.id, requireNotNull(snapshot.data))
    }

    private suspend fun verifyServerReceipt(client: Client, command: FinancialTransaction, total: Long, revision: Long) {
        val snapshot = receiptRef(client, command.id).get(Source.SERVER).await()
        assertTrue(snapshot.exists())
        assertFalse(snapshot.metadata.isFromCache)
        assertFalse(snapshot.metadata.hasPendingWrites())
        val stored = FoundationCodec.event(snapshot.id, requireNotNull(snapshot.data))
        assertTrue(FoundationCodec.sameExpectedPaymentReceipt(FoundationCodec.eventData(command), FoundationCodec.eventData(stored)))
        val current = serverPayment(client)
        assertEquals(total, current.receivedAmountMinor)
        assertEquals(revision, current.metadata.revision)
        assertEquals(command.id, current.lastReceiptOperationId)
        val ledger = user(client).collection("transactions").whereEqualTo("expectedPaymentId", payment.id).get(Source.SERVER).await()
        assertFalse(ledger.metadata.isFromCache)
        assertEquals("Only one linked financial event may post", 1, ledger.size())
    }

    @Test fun partialReceiptAtomicallyCreatesEventAndAdvancesPayment() = runBlocking {
        withTimeout(60_000) {
            val command = receipt()
            val result = accepted(first.repository.recordExpectedPaymentReceipt(command, 0))
            assertEquals(WriteState.CLOUD_CONFIRMED, result.writeState)
            assertFalse(result.replayed)
            verifyServerReceipt(second, command, 2_000_000L, 1)
        }
    }

    @Test fun secondClientReplaysIdenticalCommandAndRejectsDifferentPayload() = runBlocking {
        withTimeout(60_000) {
            val command = receipt()
            accepted(first.repository.recordExpectedPaymentReceipt(command, 0))
            val replay = accepted(second.repository.recordExpectedPaymentReceipt(command, 0))
            assertTrue(replay.replayed)
            assertEquals(WriteState.CLOUD_CONFIRMED, replay.writeState)
            rejected(second.repository.recordExpectedPaymentReceipt(receipt(3_000_000L), 0), FailureCode.CONFLICT)
            verifyServerReceipt(second, command, 2_000_000L, 1)
        }
    }

    @Test fun competingClientsAtOneRevisionPostOnlyOneReceipt() = runBlocking {
        withTimeout(60_000) {
            val before = serverPayment(first)
            assertEquals(0L, before.receivedAmountMinor)
            assertEquals(0L, before.metadata.revision)
            val a = receipt(2_000_000L)
            val b = receipt(3_000_000L)
            assertEquals(a.id, b.id) // Revision-based identity deliberately collides across clients.
            val results = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val left = async { start.await(); first.repository.recordExpectedPaymentReceipt(a, 0) }
                val right = async { start.await(); second.repository.recordExpectedPaymentReceipt(b, 0) }
                start.complete(Unit)
                listOf(left.await(), right.await())
            }
            val winners = results.filterIsInstance<CommandResult.Accepted<FinancialTransaction>>()
            assertEquals(1, winners.size)
            assertFalse(winners.single().replayed)
            val losingIndex = results.indexOfFirst { it is CommandResult.Rejected }
            assertTrue(losingIndex >= 0)
            val loser = results[losingIndex] as CommandResult.Rejected
            val losingClient = if (losingIndex == 0) first else second
            val winner = winners.single().value
            val amount = (winner.movements.single().amount as NativeAmount.Fiat).money.minor
            assertTrue(amount == 2_000_000L || amount == 3_000_000L)
            // Error classification is reported separately from atomicity and visibility.
            verifyServerReceipt(losingClient, winner, amount, 1)
            val after = serverPayment(losingClient)
            val delta = Math.subtractExact(after.receivedAmountMinor, before.receivedAmountMinor)
            assertEquals(amount, delta)
            val saved = user(losingClient).collection("transactions")
                .whereEqualTo("expectedPaymentId", payment.id).get(Source.SERVER).await()
            assertFalse(saved.metadata.isFromCache)
            assertEquals(1, saved.size())
            assertEquals(winner.id, saved.documents.single().id)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "PR3_RACE_RESULT receipts=${saved.size()} beforeMinor=${before.receivedAmountMinor} " +
                    "afterMinor=${after.receivedAmountMinor} deltaMinor=$delta revision=${after.metadata.revision} " +
                    "losingClient=${if (losingIndex == 0) "first" else "second"} loserCanRead=true errorCode=${loser.failure.code}\n")
            })
        }
    }

    @Test fun readOnlyInspectionHandlesExistingAndCancelledAbsentReceiptAndRejectsSignOut() = runBlocking {
        withTimeout(60_000) {
            val original = receipt()
            accepted(first.repository.recordExpectedPaymentReceipt(original, 0))
            val repo = second.repository
            val existing = repo.inspectExpectedPaymentReceipt(original.id, payment.id)
            assertTrue(existing is ReceiptInspection.Authoritative)
            existing as ReceiptInspection.Authoritative
            assertEquals(uid, existing.owner)
            assertNotNull(existing.operation)
            assertTrue(FoundationCodec.sameExpectedPaymentReceipt(
                FoundationCodec.eventData(original), FoundationCodec.eventData(requireNotNull(existing.operation))))
            assertEquals(2_000_000L, requireNotNull(existing.payment).receivedAmountMinor)
            assertEquals(1L, existing.payment!!.metadata.revision)
            verifyServerReceipt(first, original, 2_000_000L, 1)

            // Prepare revision 1 before cancellation, then inspect its absent operation afterwards.
            val retained = receipt(1_000_000L, revision = 1)
            accepted(first.repository.cancelExpectedPayment("local-cancel", payment.id, 1))
            val absent = repo.inspectExpectedPaymentReceipt(retained.id, payment.id)
            assertTrue(absent is ReceiptInspection.Authoritative)
            absent as ReceiptInspection.Authoritative
            assertNull(absent.operation)
            assertEquals(ExpectedPaymentState.CANCELLED, requireNotNull(absent.payment).state)
            assertEquals(2L, absent.payment!!.metadata.revision)
            assertEquals(2_000_000L, absent.payment!!.receivedAmountMinor)
            rejected(repo.recordExpectedPaymentReceipt(retained, 1), FailureCode.REJECTED)
            assertFalse(receiptRef(first, retained.id).get(Source.SERVER).await().exists())
            verifyServerReceipt(first, original, 2_000_000L, 2)
            assertEquals(ExpectedPaymentState.CANCELLED, serverPayment(first).state)

            second.auth.signOut()
            val signedOut = repo.inspectExpectedPaymentReceipt(original.id, payment.id)
            assertTrue(signedOut is ReceiptInspection.Unavailable)
            assertEquals(FailureCode.UNAUTHENTICATED, (signedOut as ReceiptInspection.Unavailable).failure.code)
            verifyServerReceipt(first, original, 2_000_000L, 2)
        }
    }

    @After fun tearDown() = runBlocking {
        // Named clients only. No production/default app or persistent user data is touched.
        for (client in clients.asReversed()) {
            client.auth.signOut()
            withTimeout(10_000) { client.firestore.terminate().await() }
        }
        clients.clear()
    }
}
