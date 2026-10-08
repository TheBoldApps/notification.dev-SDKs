package dev.notification.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class TransportTest {
    private val config = CoreConfig("project", "https://example.com/prefix/")
    private val state = ServerState("installation", "association", SubscriberState("user"), revision = 1, updatedAt = "now")

    @Test
    fun transmitsRegistrationFailuresAndDecodesServerHealth() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val registration = PushRegistration("failed", "FCM_AUTHENTICATION_FAILED", "2026-09-30T12:00:00Z")
        val failedState = state.copy(push = state.push.copy(registration = registration))
        val engine = MockEngine { request ->
            requests.add(request)
            respond(if (request.method.value == "GET") wireJson.encodeToString(failedState) else "{\"success\":true}")
        }
        val transport = HttpTransport(config, client = engine)
        try {
            transport.push("installation", "credential", PushRequest("failure", "association",
                DeviceProperties(token = "stored-token", registration = registration)))
            val body = wireJson.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
            assertEquals(buildJsonObject {
                put("status", "failed")
                put("errorCode", "FCM_AUTHENTICATION_FAILED")
            }, body["registration"])
            assertEquals(registration, transport.read("installation", "credential").push.registration)
        } finally {
            transport.close()
        }
    }

    @Test
    fun iosRegistrationAndSubscriptionUseExplicitPlatformProviderAndMetadata() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            respond(if (request.method.value == "POST") wireJson.encodeToString(Registration("credential", state)) else "{\"success\":true}")
        }
        val transport = HttpTransport(config, DeviceMetadata("1.2", "18", "cs", "Europe/Prague"), DevicePlatform.IOS, PushProvider.APNS, engine)
        try {
            assertEquals(state, transport.register("installation", "secret").state)
            transport.push("installation", "credential", PushRequest("push", "association", DeviceProperties()))

            val registration = wireJson.parseToJsonElement((requests[0].body as TextContent).text).jsonObject
            assertEquals(JsonPrimitive("ios"), registration["platform"])
            assertEquals(JsonPrimitive("18"), registration["metadata"]!!.jsonObject["osVersion"])
            assertEquals(JsonPrimitive("Europe/Prague"), registration["metadata"]!!.jsonObject["timezone"])
            assertNull(requests[0].headers["Authorization"])
            assertEquals("installation", requests[0].headers["Idempotency-Key"])
            val subscription = wireJson.parseToJsonElement((requests[1].body as TextContent).text).jsonObject
            assertEquals(JsonPrimitive("apns"), subscription["provider"])
            assertEquals(JsonNull, subscription["token"])
            assertEquals("Bearer credential", requests[1].headers["Authorization"])
            assertEquals("/prefix/v1/sdk/installations/installation/subscription", requests[1].url.encodedPath)
        } finally {
            transport.close()
        }
    }

    @Test
    fun concurrentRequestsKeepTheirOwnCredentials() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val headers = mutableListOf<String?>()
        val engine = MockEngine { request ->
            headers.add(request.headers["Authorization"])
            if (request.headers["Authorization"] == "Bearer first") {
                entered.complete(Unit)
                release.await()
            }
            respond(wireJson.encodeToString(state))
        }
        val transport = HttpTransport(config, client = engine)
        try {
            val first = async { transport.read("installation", "first") }
            entered.await()
            transport.read("installation", "second")
            release.complete(Unit)
            first.await()

            assertEquals(setOf("Bearer first", "Bearer second"), headers.toSet())
        } finally {
            transport.close()
        }
    }

    @Test
    fun cancellationPropagatesWithoutBecomingANetworkRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val transport = HttpTransport(config, client = MockEngine {
            entered.complete(Unit)
            awaitCancellation()
        })
        try {
            val job = launch { transport.read("installation", "credential") }
            entered.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
        } finally {
            transport.close()
        }
    }

    @Test
    fun errorsAndSizeLimitsAreIdenticalOnEveryTarget() = runTest {
        for ((body, status, expected) in listOf(
            Triple("invalid", HttpStatusCode.OK, "INVALID_RESPONSE"),
            Triple("", HttpStatusCode.OK, "INVALID_RESPONSE"),
            Triple("x".repeat(1024 * 1024 + 1), HttpStatusCode.OK, "RESPONSE_TOO_LARGE"),
            Triple("secret", HttpStatusCode.TooManyRequests, "HTTP_429"),
            Triple("secret", HttpStatusCode.Found, "HTTP_302")
        )) {
            val transport = HttpTransport(config, client = MockEngine { respond(body, status, headersOf("Retry-After", "120")) })
            try {
                val error = assertFailsWith<TransportException> { transport.read("installation", "credential") }
                assertEquals(expected, error.code)
                assertEquals(status == HttpStatusCode.TooManyRequests, error.retryable)
                assertFalse(error.message!!.contains("secret"))
                if (error.retryable) {
                    assertEquals(120_000L, error.retryAfterMs)
                }
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun retryAfterAndIdnaNormalizationArePortable() {
        assertEquals(120_000L, retryAfterMillis("Thu, 24 Sep 2026 12:02:00 GMT", 1790251200000))
        assertEquals(0L, retryAfterMillis("invalid"))
        assertEquals("User@xn--bcher-kva.de", normalizeEmail(" User@bücher.de "))
        for (email in listOf("a@example.com/path", "a@example.com:443", "a@example.com?x=1", "a@example.com#fragment")) {
            assertFailsWith<IllegalArgumentException> { normalizeEmail(email) }
        }
    }
}
