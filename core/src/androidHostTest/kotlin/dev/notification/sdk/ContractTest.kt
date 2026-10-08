package dev.notification.sdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun subscriptionRejectionPreservesBackendErrorCode() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(CoreConfig("project", server.url("/").toString(), allowLocalhostHttp = true))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"PROVIDER_CONFIGURATION_REQUIRED","message":"Exactly one active matching provider configuration is required.","requestId":"test"}"""))

            try {
                transport.push("i", "c", PushRequest("op", "a", DeviceProperties(token = "token")))
                fail("Expected subscription rejection")
            } catch (error: TransportException) {
                assertEquals("PROVIDER_CONFIGURATION_REQUIRED", error.code)
                assertEquals(409, error.httpStatus)
                assertFalse(error.retryable)
            }
        }
    }

    @Test fun unusableErrorBodiesFallBackToHttpStatus() {
        for (body in listOf("", "not json", "{}", "[]", """{"code":null}""", """{"code":409}""", """{"code":" "}""")) {
            assertEquals("HTTP_409", httpErrorCode(body, 409))
        }
    }

    @Test fun documentedFixturesDecodeUsingPublicModels() {
        val state = wireJson.decodeFromString<ServerState>(javaClass.getResource("/state.example.json")!!.readText())
        val push = wireJson.decodeFromString<NotificationPayload>(javaClass.getResource("/notification.example.json")!!.readText())
        assertEquals("premium", state.user.tags.getValue("plan").content)
        assertEquals(state.associationId, push.associationId)
        assertNotNull(state.user.email)
    }

    @Test fun transportUsesSingleUserPatchWithoutIdentityProof() = runTest {
        MockWebServer().use { server ->
            server.start()
            val transport = HttpTransport(CoreConfig("project", server.url("/").toString(), allowLocalhostHttp = true))
            val snapshot = ServerState("installation", "association", SubscriberState("user", tags = mapOf("plan" to JsonPrimitive("premium"))), revision = 3, updatedAt = "2026-09-18T00:00:00Z")
            server.enqueue(MockResponse().setBody(wireJson.encodeToString(snapshot)))
            assertEquals(snapshot, transport.read("installation", "credential"))
            val get = server.takeRequest(); assertEquals("GET", get.method)
            assertEquals("/v1/sdk/installations/installation", get.path)
            assertEquals("Bearer credential", get.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(wireJson.encodeToString(snapshot)))
            val changes = UserChanges(EmailChange(1, address = "a@example.com", optedIn = false),
                mapOf("plan" to TagChange(1, JsonPrimitive("premium")), "removed" to TagChange(1, null)))
            assertEquals(snapshot, transport.updateUser("installation", "credential",
                UserRequest("op", "association", changes)))
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/v1/sdk/installations/installation/user", patch.path)
            assertNull(patch.getHeader("X-Identity-Token"))
            assertEquals("op", patch.getHeader("Idempotency-Key"))
            assertEquals(wireJson.parseToJsonElement("""{"associationId":"association","email":{"address":"a@example.com","optedIn":false},"tags":{"plan":"premium"},"removeTags":["removed"]}"""),
                wireJson.parseToJsonElement(patch.body.readUtf8()))

        }
    }
    @Test fun operationRejectionInsideSuccessfulHttpIsNotTreatedAsSuccess() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(CoreConfig("project", server.url("/").toString(), allowLocalhostHttp = true))
            server.enqueue(MockResponse().setBody("""{"results":[{"operationId":"op","succeeded":false,"code":"STALE_ASSOCIATION","retryable":false}]}"""))
            try { transport.sendEvent("i", "c", QueuedEvent("op", "a", Event.Track("test", JsonObject(emptyMap()), 0))); fail() }
            catch (e: TransportException) { assertFalse(e.retryable); assertEquals("STALE_ASSOCIATION", e.code) }
        }
    }
    @Test fun throttlingRetryAfterIsParsed() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(CoreConfig("project", server.url("/").toString(), allowLocalhostHttp = true))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            try { transport.read("i", "c"); fail() } catch (e: TransportException) { assertTrue(e.retryable); assertEquals(120000, e.retryAfterMs) }
        }
    }
    @Test fun remoteCleartextIsRejected() {
        try { HttpTransport(CoreConfig("project", "http://example.com/", allowLocalhostHttp = true)); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun emailNormalizationPreservesLocalPartAndRejectsInvalidDomain() {
        assertEquals("User@example.com", normalizeEmail(" User@EXAMPLE.COM "))
        for (email in listOf("invalid", "a@@example.com", "a@-example.com", "a b@example.com", ".a@example.com")) {
            try { normalizeEmail(email); fail(email) } catch (_: IllegalArgumentException) { }
        }
    }
}
