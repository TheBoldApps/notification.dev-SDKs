package dev.notification.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoreNotificationTest {
    private class Fixture {
        val config = CoreConfig("project", "https://example.com")
        var snapshot = ServerState("installation", "association", SubscriberState("user"), revision = 1, updatedAt = "now")
        var stored = wireJson.encodeToString(StoredState(
            "installation", "secret", configurationId = "project|https://example.com",
            credential = "credential", snapshot = snapshot
        ))
        var identityStatus = HttpStatusCode.OK
        val clears = mutableListOf<SdkState>()
        var clearFailure: Exception? = null
        private val transport = HttpTransport(config, client = MockEngine {
            respond(wireJson.encodeToString(snapshot), identityStatus)
        })
        val client = CoreClient(config, object : SecureStorage {
            override suspend fun load(): String = stored

            override suspend fun save(state: String) {
                stored = state
            }
        }, transport, clearNotifications = ::clear)

        private fun clear() {
            clears.add(client.state.value)
            clearFailure?.let { throw it }
        }

        fun payload(id: String = "delivery") = NotificationPayload(
            deliveryId = id, associationId = "association", expiresAt = Long.MAX_VALUE,
            title = "Title", body = "Body"
        )
    }

    @Test
    fun acceptedNotificationsEmitOnceAfterPersistenceAndBeforeDisplay() = runTest {
        val fixture = Fixture()
        val client = fixture.client

        try {
            client.start()
            val events = mutableListOf<NotificationEvent>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.notifications.collect { event ->
                    assertTrue(decodeStoredState(fixture.stored).seenDeliveries.containsKey(event.notification.deliveryId))
                    events.add(event)
                }
            }
            val payload = fixture.payload()

            assertTrue(client.receive(payload))
            runCurrent()
            client.displayIfEligible(payload) {
                assertEquals(listOf(NotificationEvent(payload)), events)
            }
            assertFalse(client.receive(payload))
            assertFalse(client.receive(fixture.payload("expired").copy(expiresAt = 0)))
            assertFalse(client.receive(fixture.payload("wrong-user").copy(associationId = "other")))
            assertFalse(client.receive(fixture.payload("wrong-version").copy(version = 2)))
            client.setPushOptedIn(false)
            assertFalse(client.receive(fixture.payload("opted-out")))
            runCurrent()

            assertEquals(listOf(NotificationEvent(payload)), events)
            collector.cancel()
            assertTrue(client.notifications.replayCache.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun notificationsRemainLiveOnlyAndDisplayRechecksEligibility() = runTest {
        val fixture = Fixture()
        val client = fixture.client

        try {
            client.start()
            assertTrue(client.receive(fixture.payload("before-subscriber")))
            val events = mutableListOf<NotificationEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.notifications.collect { events.add(it) }
            }
            runCurrent()
            assertTrue(events.isEmpty())

            val payload = fixture.payload()
            assertTrue(client.receive(payload))
            client.setPushOptedIn(false)
            client.displayIfEligible(payload) { fail("Opt-out must prevent display after receipt") }
            runCurrent()

            assertEquals(listOf(NotificationEvent(payload)), events)
        } finally {
            client.close()
        }
    }

    @Test
    fun clearingFollowsSuccessfulStateChangesOnly() = runTest {
        val fixture = Fixture()
        val client = fixture.client

        try {
            client.start()
            client.setPushOptedIn(true)
            assertTrue(fixture.clears.isEmpty())
            client.setPushOptedIn(false)
            assertFalse(fixture.clears.single().pushOptedIn)
            assertFalse(decodeStoredState(fixture.stored).device.optedIn)

            fixture.snapshot = fixture.snapshot.copy(user = fixture.snapshot.user.copy(externalId = "customer"))
            client.login("customer")
            assertEquals(1, fixture.clears.size)

            fixture.snapshot = fixture.snapshot.copy(associationId = "new-association")
            client.login("customer")
            assertEquals("new-association", fixture.clears.last().associationId)
            assertEquals(2, fixture.clears.size)

            client.logout()
            assertEquals(3, fixture.clears.size)
            assertNull(fixture.clears.last().associationId)
            assertTrue(fixture.clears.last().identityChangePending)
        } finally {
            client.close()
        }
    }

    @Test
    fun failedIdentityOperationsDoNotClearNotifications() = runTest {
        val fixture = Fixture()
        val client = fixture.client

        try {
            client.start()
            assertFailsWith<IllegalArgumentException> { client.login("") }
            fixture.identityStatus = HttpStatusCode.ServiceUnavailable
            assertFailsWith<TransportException> { client.login("customer") }
            assertFailsWith<SdkException> { client.logout() }

            assertTrue(fixture.clears.isEmpty())
            assertEquals("association", client.state.value.associationId)
        } finally {
            client.close()
        }
    }

    @Test
    fun clearingFailuresPropagateAfterEachStateChange() = runTest {
        for (operation in listOf("login", "logout", "opt-out")) {
            val fixture = Fixture()
            val client = fixture.client
            val failure = IllegalStateException("Native clearing failed")
            fixture.clearFailure = failure

            try {
                client.start()
                val caught = assertFailsWith<IllegalStateException> {
                    when (operation) {
                        "login" -> {
                            fixture.snapshot = fixture.snapshot.copy(
                                associationId = "new-association",
                                user = fixture.snapshot.user.copy(externalId = "customer")
                            )
                            client.login("customer")
                        }
                        "logout" -> client.logout()
                        else -> client.setPushOptedIn(false)
                    }
                }

                assertSame(failure, caught)
                assertEquals(client.state.value, fixture.clears.single())
                val persisted = decodeStoredState(fixture.stored)

                when (operation) {
                    "login" -> assertEquals("new-association", persisted.snapshot!!.associationId)
                    "logout" -> assertTrue(persisted.identity.isLoggingOut)
                    else -> assertFalse(persisted.device.optedIn)
                }
            } finally {
                client.close()
            }
        }
    }
}
