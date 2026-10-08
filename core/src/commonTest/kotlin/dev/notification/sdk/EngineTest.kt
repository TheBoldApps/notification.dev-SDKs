package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.*

internal class MemoryStore : StateStore {
    var value: StoredState? = null
    var failSave = false

    override suspend fun load() = value

    override suspend fun save(state: StoredState) {
        if (failSave) {
            throw IOException("disk unavailable")
        }
        value = state
    }
}

internal class FakeTransport : Transport {
    var snapshot = ServerState("", "anonymous", SubscriberState("user"), revision = 1, updatedAt = "now")
    var failure: TransportException? = null
    var userFailure: TransportException? = null
    var readFailure: TransportException? = null
    var identityFailure: TransportException? = null
    var registrationFailure: TransportException? = null
    val registrations = mutableListOf<Pair<String, String>>()
    var eventFailure: TransportException? = null
    var afterUser: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var afterPush: suspend () -> Unit = {}
    var loseUserResponse = false
    var loseLoginResponse = false
    var losePushResponse = false
    var pushFailure: TransportException? = null
    val pushResponses = mutableSetOf<String>()
    val requests = mutableListOf<UserRequest>()
    val events = mutableListOf<QueuedEvent>()
    val pushes = mutableListOf<PushRequest>()
    val userResponses = mutableMapOf<String, ServerState>()
    val identityResponses = mutableMapOf<String, ServerState>()
    var reads = 0

    override suspend fun register(id: String, secret: String): Registration {
        registrations.add(id to secret)
        registrationFailure?.let { throw it }
        failure?.let { throw it }
        if (snapshot.installationId.isNotEmpty() && snapshot.installationId != id) {
            snapshot = ServerState(id, "anonymous:$id", SubscriberState("user:$id"), revision = 1, updatedAt = "now")
        }
        snapshot = snapshot.copy(installationId = id)

        return Registration("credential", snapshot)
    }

    override suspend fun read(id: String, credential: String): ServerState {
        reads++
        val response = snapshot
        beforeRead()
        readFailure?.let { throw it }
        failure?.let { throw it }

        return response
    }

    override suspend fun updateUser(id: String, credential: String, request: UserRequest): ServerState {
        requests.add(request)
        failure?.let { throw it }
        userFailure?.let { throw it }
        val response = userResponses.getOrPut(request.id) {
            val user = request.changes.applyTo(UserProperties(snapshot.user.email, snapshot.user.tags))
            snapshot = snapshot.copy(user = snapshot.user.copy(email = user.email, tags = user.tags), revision = snapshot.revision + 1)
            snapshot
        }
        afterUser()
        if (loseUserResponse) {
            loseUserResponse = false
            throw TransportException("NETWORK", true)
        }

        return response
    }

    override suspend fun sendEvent(id: String, credential: String, event: QueuedEvent) {
        failure?.let { throw it }
        eventFailure?.let { throw it }
        events.add(event)
    }

    override suspend fun identity(id: String, credential: String, operationId: String, associationId: String,
        externalId: String?): ServerState {
        identityFailure?.let { throw it }
        failure?.let { throw it }
        val response = identityResponses.getOrPut(operationId) {
            snapshot = snapshot.copy(associationId = "association:$operationId",
                user = if (externalId == null) SubscriberState("anonymous:$operationId") else snapshot.user.copy(externalId = externalId),
                revision = snapshot.revision + 1)
            snapshot
        }
        if (loseLoginResponse) {
            loseLoginResponse = false
            throw TransportException("NETWORK", true)
        }

        return response
    }

    override suspend fun push(id: String, credential: String, request: PushRequest) {
        pushes.add(request)
        failure?.let { throw it }
        pushFailure?.let { throw it }
        if (pushResponses.add(request.id)) {
            snapshot = snapshot.copy(push = PushSubscription(request.device.optedIn, request.device.token != null && request.device.registration?.status != "failed", request.device.permission, request.device.registration ?: snapshot.push.registration),
                revision = snapshot.revision + 1)
        }
        afterPush()
        if (losePushResponse) {
            losePushResponse = false
            throw TransportException("NETWORK", true)
        }
    }
}

class EngineTest {
    private suspend fun setup(): Triple<Engine, MemoryStore, FakeTransport> {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        engine.sync()

        return Triple(engine, store, transport)
    }

    @Test fun offlineChangesAreImmediateCoalescedAndSurviveRestart() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        engine.setEmail("first@example.com", true)
        engine.setEmail("last@example.com", true)
        engine.setEmailOptedIn(false)
        engine.setTags(mapOf("plan" to JsonPrimitive("old"), "removed" to JsonPrimitive(true)))
        engine.setTags(mapOf("plan" to JsonPrimitive("premium"), "removed" to null))
        assertEquals("last@example.com", engine.state.value.user.email!!.address)
        assertFalse(engine.state.value.user.email!!.optedIn)
        assertTrue(engine.state.value.hasPendingChanges)
        assertTrue(store.value!!.events.isEmpty())

        val restarted = Engine(store, transport)
        restarted.start()
        assertEquals(engine.state.value.user, restarted.state.value.user)
        assertEquals(SyncResult.Done, restarted.sync())
        assertEquals(1, transport.requests.size)
        assertEquals(0, transport.reads)
        assertEquals(mapOf("plan" to JsonPrimitive("premium")), restarted.state.value.user.tags)
        assertFalse(restarted.state.value.hasPendingChanges)
    }

    @Test fun newerEditsSurviveAnInFlightUploadIncludingSameValue() = runTest {
        val (engine, store, transport) = setup()
        engine.setEmail("a@example.com", true)
        engine.setTags(mapOf("plan" to JsonPrimitive("a")))
        val sent = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        transport.afterUser = { sent.complete(Unit); finish.await() }
        val job = launch { engine.sync() }
        sent.await()
        engine.setEmail("b@example.com", false)
        engine.setTags(mapOf("plan" to JsonPrimitive("b")))
        engine.setTags(mapOf("plan" to JsonPrimitive("a")))
        finish.complete(Unit)
        job.join()
        assertEquals("b@example.com", engine.state.value.user.email!!.address)
        assertFalse(store.value!!.user.changes.isEmpty)

        engine.sync()
        assertEquals(2, transport.requests.size)
        assertEquals("b@example.com", transport.snapshot.user.email!!.address)
        assertFalse(engine.state.value.hasPendingChanges)
    }

    @Test fun refreshPreservesLocalEditsAndBackendTags() = runTest {
        val (engine, _, transport) = setup()
        engine.setTags(mapOf("plan" to JsonPrimitive("premium")))
        transport.snapshot = transport.snapshot.copy(user = transport.snapshot.user.copy(
            tags = mapOf("backend" to JsonPrimitive(true))), revision = 20)
        engine.refresh()
        assertEquals(setOf("plan", "backend"), engine.state.value.user.tags.keys)
        engine.sync()
        assertEquals(setOf("plan", "backend"), transport.snapshot.user.tags.keys)
    }

    @Test fun failedStorageDoesNotBlockLocalChangesOrRemoteSync() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val diagnostics = mutableListOf<SdkError>()
        val engine = Engine(store, transport, diagnostic = { diagnostics.add(it) })
        engine.start()
        store.failSave = true
        engine.setEmail("a@example.com", true)
        assertEquals("a@example.com", engine.state.value.user.email!!.address)
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals("a@example.com", transport.snapshot.user.email!!.address)
        assertTrue(diagnostics.any { it.code == "STORAGE_FAILURE" })
    }

    @Test fun lostResponseRetriesSameRequestAfterRestartBeforeNewEdits() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.setEmail("a@example.com", true)
        transport.loseUserResponse = true
        assertNotEquals(SyncResult.Done, engine.sync())
        engine.setEmail("b@example.com", false)
        assertEquals(1, transport.requests.size)

        clock += 60_000
        val restarted = Engine(store, transport, now = { clock })
        restarted.start()
        assertNotEquals(SyncResult.Done, restarted.sync())
        assertEquals(transport.requests[0], transport.requests[1])
        assertEquals("b@example.com", restarted.state.value.user.email!!.address)
        assertEquals(SyncResult.Done, restarted.sync())
        assertNotEquals(transport.requests[1].id, transport.requests[2].id)
        assertEquals(2, transport.userResponses.size)
    }

    @Test fun oldIdempotentResponseIsAcknowledgedWithoutRollingBackNewerState() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.setEmail("a@example.com", true)
        transport.loseUserResponse = true
        assertNotEquals(SyncResult.Done, engine.sync())
        transport.snapshot = transport.snapshot.copy(revision = 100,
            user = transport.snapshot.user.copy(tags = mapOf("backend" to JsonPrimitive(true))))
        engine.refresh()
        engine.setPushOptedIn(false)
        clock += 60_000
        assertEquals(SyncResult.Done, engine.sync())
        assertFalse(engine.state.value.hasPendingChanges)
        assertFalse(engine.state.value.pushOptedIn)
        assertEquals(JsonPrimitive(true), engine.state.value.user.tags["backend"])
        assertEquals(1, transport.userResponses.size)
    }

    @Test fun cachedUserResponseDoesNotUndoNewerConfirmedPushPreference() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.setEmail("a@example.com", true)
        transport.loseUserResponse = true
        assertNotEquals(SyncResult.Done, engine.sync())
        engine.setPushOptedIn(false)
        clock += 60_000
        assertEquals(SyncResult.Done, engine.sync())
        assertFalse(engine.state.value.pushOptedIn)
        assertFalse(engine.state.value.hasPendingChanges)
    }

    @Test fun rejectedChangesStopUntilCorrected() = runTest {
        val (engine, _, transport) = setup()
        engine.setEmail("a@example.com", true)
        transport.userFailure = TransportException("HTTP_403", false)
        assertEquals(SyncResult.Done, engine.sync())
        engine.sync()
        assertEquals(1, transport.requests.size)
        assertEquals("HTTP_403", engine.state.value.lastError!!.code)
        assertEquals("a@example.com", engine.state.value.user.email!!.address)

        engine.setEmail("corrected@example.com", true)
        transport.userFailure = null
        engine.sync()
        assertFalse(engine.state.value.hasPendingChanges)
    }

    @Test fun permanentFailureOfOlderUploadDoesNotBlockNewerEdit() = runTest {
        val (engine, _, transport) = setup()
        engine.setEmail("a@example.com", true)
        transport.afterUser = {
            engine.setEmail("b@example.com", true)
            throw TransportException("HTTP_400", false)
        }
        assertNotEquals(SyncResult.Done, engine.sync())
        transport.afterUser = {}
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals("b@example.com", transport.snapshot.user.email!!.address)
    }

    @Test fun logoutWithRejectedCredentialRegistersFreshAnonymousInstallation() = runTest {
        val (engine, store, transport) = setup()
        engine.login("previous-user")
        engine.updateDevice(token = "push-token")
        engine.setPushOptedIn(false)
        engine.setEmail("old@example.com", true)
        engine.track("old-event", JsonObject(emptyMap()), "old-event")
        val previous = store.value!!
        transport.identityFailure = TransportException("UNAUTHORIZED", false, httpStatus = 401)

        engine.logout()
        assertEquals(SyncResult.Done, engine.sync())

        val current = store.value!!
        assertNotEquals(previous.installationId, current.installationId)
        assertNotEquals(previous.registrationSecret, current.registrationSecret)
        assertEquals(previous.configurationId, current.configurationId)
        assertEquals(previous.device, current.device)
        assertEquals(Availability.AVAILABLE, engine.state.value.availability)
        assertNull(engine.state.value.externalId)
        assertNull(engine.state.value.user.email)
        assertNull(engine.state.value.lastError)
        assertFalse(engine.state.value.hasPendingChanges)
        assertTrue(transport.events.isEmpty())
        assertTrue(transport.requests.isEmpty())
        assertEquals("push-token", transport.pushes.last().device.token)
        assertEquals(current.snapshot!!.associationId, transport.pushes.last().associationId)
        assertEquals(2, transport.registrations.size)
    }

    @Test fun anonymousRegistrationAfterRejectedLogoutSurvivesRestart() = runTest {
        val (engine, store, transport) = setup()
        engine.login("previous-user")
        transport.identityFailure = TransportException("HTTP_401", false)
        transport.registrationFailure = TransportException("NETWORK", true)
        engine.logout()

        assertTrue(engine.sync() is SyncResult.Retry)
        val registration = transport.registrations.last()
        assertTrue(engine.state.value.identityChangePending)
        assertNull(engine.state.value.externalId)
        assertNull(store.value!!.credential)
        assertNull(store.value!!.snapshot)

        transport.registrationFailure = null
        val restarted = Engine(store, transport)
        restarted.start()
        assertEquals(SyncResult.Done, restarted.sync())
        assertEquals(registration, transport.registrations.last())
        assertEquals(Availability.AVAILABLE, restarted.state.value.availability)
        assertFalse(restarted.state.value.identityChangePending)
        assertNull(restarted.state.value.externalId)
    }

    @Test fun otherLogoutFailuresDoNotReplaceInstallation() = runTest {
        for (error in listOf(TransportException("NETWORK", true), TransportException("HTTP_403", false))) {
            val (engine, store, transport) = setup()
            val previous = store.value!!
            transport.identityFailure = error
            engine.logout()
            engine.sync()

            assertEquals(previous.installationId, store.value!!.installationId)
            assertEquals(previous.credential, store.value!!.credential)
            assertTrue(engine.state.value.identityChangePending)
            assertEquals(1, transport.registrations.size)
        }
    }

    @Test fun logoutImmediatelyHidesUserDuringInFlightRead() = runTest {
        val (engine, _, transport) = setup()
        engine.setEmail("a@example.com", true)
        engine.sync()
        val reading = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        transport.beforeRead = { reading.complete(Unit); finish.await() }
        val job = launch { engine.refresh() }
        reading.await()
        engine.logout()
        assertNull(engine.state.value.user.email)
        finish.complete(Unit)
        job.join()
        assertNull(engine.state.value.user.email)
        assertNull(engine.state.value.associationId)
        engine.sync()
        assertFalse(engine.state.value.identityChangePending)
    }

    @Test fun logoutDuringUploadDoesNotRestoreOldChanges() = runTest {
        val (engine, store, transport) = setup()
        engine.setEmail("a@example.com", true)
        transport.afterUser = { engine.logout() }
        assertNotEquals(SyncResult.Done, engine.sync())
        assertNull(engine.state.value.user.email)
        assertTrue(store.value!!.user.changes.isEmpty)
        assertNull(store.value!!.user.request)
        engine.sync()
        assertNull(engine.state.value.user.email)
    }

    @Test fun accountSwitchDropsOldChangesAndEvents() = runTest {
        val (engine, store, _) = setup()
        engine.setEmail("a@example.com", true)
        engine.track("old-event", JsonObject(emptyMap()), "event")
        engine.login("other-user")
        assertTrue(store.value!!.user.changes.isEmpty)
        assertTrue(store.value!!.events.isEmpty())
        assertNull(engine.state.value.user.email)
    }

    @Test fun ambiguousLoginRetainsIdentitySafeguards() = runTest {
        val (engine, store, transport) = setup()
        transport.loseLoginResponse = true
        try {
            engine.login("user")
            fail()
        } catch (_: TransportException) { }
        val id = store.value!!.identity.transition!!.id
        val restarted = Engine(store, transport)
        restarted.start()

        for (operation in listOf<suspend () -> Unit>(
            { restarted.logout() },
            { restarted.login("different-user") },
            { restarted.setEmail("a@example.com", true) }
        )) {
            try {
                operation()
                fail("Ambiguous login must be resolved first")
            } catch (e: SdkException) {
                assertEquals("IDENTITY_CHANGE_PENDING", e.error.code)
            }
        }

        restarted.login("user")
        assertEquals(setOf(id), transport.identityResponses.keys)
        assertFalse(restarted.state.value.identityChangePending)
    }

    @Test fun pendingLogoutSurvivesRestartAndReusesItsRequest() = runTest {
        val (engine, store, transport) = setup()
        engine.setEmail("a@example.com", true)
        engine.sync()
        engine.logout()
        val id = store.value!!.identity.transition!!.id
        // The fake loses identity responses for both login and logout.
        transport.loseLoginResponse = true
        assertEquals(SyncResult.Retry(0), engine.sync())

        val restarted = Engine(store, transport)
        restarted.start()
        restarted.logout()
        assertNull(restarted.state.value.user.email)
        assertNull(restarted.state.value.associationId)
        assertTrue(restarted.state.value.identityChangePending)
        assertEquals(SyncResult.Done, restarted.sync())
        assertEquals(setOf(id), transport.identityResponses.keys)
        assertFalse(restarted.state.value.identityChangePending)
    }

    @Test fun pushOptOutDuringUploadIsImmediateAndGetsItsOwnRequest() = runTest {
        val (engine, _, transport) = setup()
        engine.updateDevice(token = "fcm")
        transport.afterPush = { engine.setPushOptedIn(false) }
        assertNotEquals(SyncResult.Done, engine.sync())
        assertFalse(engine.state.value.pushOptedIn)
        transport.afterPush = {}
        assertEquals(SyncResult.Done, engine.sync())
        assertFalse(transport.pushes.last().device.optedIn)
        assertNotEquals(transport.pushes.takeLast(2)[0].id, transport.pushes.last().id)
    }

    @Test fun deviceRetryKeepsCapturedRequestAcrossRestartAndUploadsLatestValuesNext() = runTest {
        val (engine, store, transport) = setup()
        engine.updateDevice(token = "first-token")
        transport.losePushResponse = true
        assertEquals(SyncResult.Retry(0), engine.sync())
        val captured = store.value!!.pendingDevice!!.request
        engine.updateDevice(token = "latest-token")
        engine.setPushOptedIn(false)
        val restarted = Engine(store, transport)
        restarted.start()
        assertEquals(SyncResult.Pending, restarted.sync())
        assertEquals(captured, transport.pushes.last())
        assertFalse(restarted.state.value.pushOptedIn)
        assertEquals(SyncResult.Done, restarted.sync())
        assertEquals("latest-token", transport.pushes.last().device.token)
        assertFalse(transport.pushes.last().device.optedIn)
        assertNull(store.value!!.pendingDevice)
    }

    @Test fun rejectedDeviceStopsRetryingAndCanBeCorrected() = runTest {
        val (engine, store, transport) = setup()
        engine.updateDevice(token = "bad-token", status = PushPermissionStatus())
        transport.pushFailure = TransportException("HTTP_400", false)
        assertEquals(SyncResult.Done, engine.sync())
        assertTrue(store.value!!.pendingDevice!!.rejected)
        val attempts = transport.pushes.size
        engine.updateDevice(status = PushPermissionStatus())
        engine.sync()
        assertEquals(attempts, transport.pushes.size)

        engine.updateDevice(token = "correct-token")
        transport.pushFailure = null
        assertEquals(SyncResult.Done, engine.sync())
        assertNull(store.value!!.pendingDevice)
    }

    @Test fun changedDeviceDuringRejectionIsStillPending() = runTest {
        val (engine, store, transport) = setup()
        engine.updateDevice(token = "first-token")
        transport.afterPush = {
            engine.updateDevice(token = "new-token")
            throw TransportException("HTTP_400", false)
        }
        assertEquals(SyncResult.Pending, engine.sync())
        assertFalse(store.value!!.pendingDevice!!.rejected)
        transport.afterPush = {}
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals("new-token", transport.pushes.last().device.token)
    }

    @Test fun eventsReportRetryAfterAndTerminalRejectionDoesNotBlockLaterEvents() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 10_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.track("first", JsonObject(emptyMap()), "1")
        transport.eventFailure = TransportException("HTTP_429", true, 120_000)
        assertEquals(SyncResult.Retry(120_000), engine.sync())
        assertTrue(transport.events.isEmpty())
        engine.track("second", JsonObject(emptyMap()), "2")
        clock += 60_001
        transport.eventFailure = TransportException("HTTP_400", false)
        assertEquals(SyncResult.Done, engine.sync())
        assertTrue(store.value!!.events.isEmpty())
        transport.eventFailure = null
        engine.track("third", JsonObject(emptyMap()), "3")
        engine.sync()
        assertEquals("3", transport.events.single().id)
    }

    @Test fun eventLimitsAndExpiryDoNotBlockUserChangesOrLogout() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 100L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        val event = QueuedEvent("id", null, Event.Track("test", JsonObject(emptyMap()), clock))
        store.value = store.value!!.copy(events = (1..1000).map { event.copy(id = "$it") })
        val full = Engine(store, transport, now = { clock })
        full.start()
        try {
            full.track("overflow", JsonObject(emptyMap()), "overflow")
            fail()
        } catch (e: SdkException) {
            assertEquals("QUEUE_FULL", e.error.code)
        }
        full.setEmail("a@example.com", true)
        clock += RETENTION + 1
        assertEquals(SyncResult.Done, full.sync())
        assertTrue(store.value!!.events.isEmpty())
        assertEquals("a@example.com", transport.snapshot.user.email!!.address)
        full.logout()
    }

    @Test fun notificationDedupeAndDurableOpenAcknowledgmentRemain() = runTest {
        val (engine, store, transport) = setup()
        val payload = NotificationPayload(deliveryId = "delivery", associationId = engine.state.value.associationId!!,
            expiresAt = currentTimeMillis() + 60_000, title = "title", body = "body")
        assertTrue(engine.receive(payload))
        assertFalse(engine.receive(payload))
        assertTrue(engine.open(payload))
        engine.sync()
        assertEquals(2, transport.events.size)
        val restarted = Engine(store, transport)
        restarted.start()
        assertEquals(1, restarted.opens.value.size)
        restarted.acknowledgeOpen(restarted.opens.value.single().interactionId)
        assertTrue(restarted.opens.value.isEmpty())
    }

    @Test fun successfulRetryClearsTransientErrorAndIdleSyncDoesNotReadAgain() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.track("purchase", JsonObject(emptyMap()), "event")
        transport.eventFailure = TransportException("NETWORK", true)
        assertNotEquals(SyncResult.Done, engine.sync())
        assertEquals("NETWORK", engine.state.value.lastError!!.code)
        transport.eventFailure = null
        clock += 60_000
        assertEquals(SyncResult.Done, engine.sync())
        assertNull(engine.state.value.lastError)
        val reads = transport.reads
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(reads, transport.reads)
        engine.requestRefresh()
        engine.sync()
        assertEquals(reads + 1, transport.reads)
    }

    @Test fun configurationAndStaleSnapshotChecksRemain() = runTest {
        val (engine, store, transport) = setup()
        try {
            Engine(store, transport, configurationId = "other-project").start()
            fail()
        } catch (e: SdkException) {
            assertEquals("CONFIGURATION_CHANGED", e.error.code)
        }
        transport.snapshot = transport.snapshot.copy(revision = 0)
        try {
            engine.refresh()
            fail()
        } catch (e: SdkException) {
            assertEquals("STALE_RESPONSE", e.error.code)
        }
    }
}
