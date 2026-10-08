package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

class StoredStateDecodingTest {
    @Test
    fun pendingWorkAndIdentityRoundTripWithoutChangingRequestIds() {
        for (transition in listOf(IdentityTransition.Login("login", "account"), IdentityTransition.Logout("logout"))) {
            val state = StoredState(
                "installation", "secret", credential = "credential",
                identity = IdentityState(transition),
                user = PendingUser(changes = UserChanges(email = EmailChange(1, address = "a@example.com", optedIn = true))),
                events = listOf(QueuedEvent("event", "association", Event.Track("purchase", JsonObject(emptyMap()), 10))),
                pendingDevice = PendingDevice(PushRequest("push", "association", DeviceProperties(token = "token"))),
                retry = RetrySchedule(2, 12345)
            )

            assertEquals(state, decodeStoredState(wireJson.encodeToString(state)))
        }
    }

    @Test
    fun serializedAmbiguousLoginKeepsIdentityGuardAfterRestart() = runTest {
        val original = StoredState("installation", "secret", identity = IdentityState(IdentityTransition.Login("request", "account")))
        val store = MemoryStore().apply { value = decodeStoredState(wireJson.encodeToString(original)) }
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()

        val error = assertFailsWith<SdkException> { engine.logout() }
        assertEquals("IDENTITY_CHANGE_PENDING", error.error.code)
        engine.login("account")
        assertEquals(setOf("request"), transport.identityResponses.keys)
    }

    @Test
    fun malformedStateFailsInsteadOfCreatingAnotherIdentity() {
        assertFails { decodeStoredState("{}") }
        assertFails { decodeStoredState("invalid") }
    }
}
