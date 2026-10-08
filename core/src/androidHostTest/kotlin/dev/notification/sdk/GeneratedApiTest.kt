package dev.notification.sdk

import dev.notification.sdk.generated.model.MetadataUpdate
import dev.notification.sdk.generated.model.SubscribersInstallationMetadata
import dev.notification.sdk.generated.model.UserPatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class GeneratedApiTest {
    private val state = ServerState("installation", "association", SubscriberState("user"), revision = 1, updatedAt = "now")

    private fun config(server: MockWebServer, prefix: String = "/") =
        CoreConfig("project", server.url(prefix).toString(), allowLocalhostHttp = true)

    private fun MockWebServer.replyState() {
        enqueue(MockResponse().setBody(wireJson.encodeToString(state)))
    }

    private fun MockWebServer.next(method: String, path: String, key: String?, credential: String? = "credential"): RecordedRequest {
        val request = takeRequest(2, TimeUnit.SECONDS) ?: error("No API request received")
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals(credential?.let { "Bearer $it" }, request.getHeader("Authorization"))

        return request
    }

    private fun RecordedRequest.bodyJson(): JsonObject = wireJson.parseToJsonElement(body.readUtf8()).jsonObject

    @Test
    fun registrationIdentityReadsAndMetadataUseGeneratedEndpoints() = runTest {
        MockWebServer().use { server ->
            val metadata = DeviceMetadata(appVersion = "1.2", locale = "en")
            val transport = HttpTransport(config(server), metadata)
            val registration = Registration("credential", state)
            server.enqueue(MockResponse().setBody(wireJson.encodeToString(registration)))

            assertEquals(registration, transport.register("installation", "secret"))
            val body = server.next("POST", "/v1/sdk/installations", "installation", null).bodyJson()
            assertEquals(
                wireJson.parseToJsonElement("""{"projectId":"project","installationId":"installation","registrationSecret":"secret","platform":"android","sdkVersion":"0.1.0","metadata":{"appVersion":"1.2","locale":"en"}}"""),
                body
            )

            server.replyState()
            assertEquals(state, transport.read("installation", "credential"))
            server.next("GET", "/v1/sdk/installations/installation", null)

            server.replyState()
            assertEquals(state, transport.identity("installation", "credential", "login", "association", "customer"))
            assertEquals(
                wireJson.parseToJsonElement("""{"associationId":"association","externalId":"customer"}"""),
                server.next("POST", "/v1/sdk/installations/installation/login", "login").bodyJson()
            )

            server.replyState()
            transport.identity("installation", "credential", "logout", "association", null)
            assertEquals(
                wireJson.parseToJsonElement("""{"associationId":"association"}"""),
                server.next("POST", "/v1/sdk/installations/installation/logout", "logout").bodyJson()
            )

            server.replyState()
            val http = ApiHttpClient(config(server))
            val updated = http.execute("credential") { it.updateInstallationMetadata("installation", "metadata", MetadataUpdate("association", SubscribersInstallationMetadata(appVersion = "1.2", locale = "en"))) }
            assertEquals(state.installationId, updated.installationId)
            assertEquals(
                wireJson.parseToJsonElement("""{"associationId":"association","metadata":{"appVersion":"1.2","locale":"en"}}"""),
                server.next("PATCH", "/v1/sdk/installations/installation", "metadata").bodyJson()
            )
        }
    }

    @Test
    fun generatedResponsesMapLongRevisionsMixedTagsAndFuturePermissions() = runTest {
        MockWebServer().use { server ->
            val expected = ServerState(
                "installation", "association",
                SubscriberState("user", tags = mapOf(
                    "enabled" to JsonPrimitive(true), "count" to JsonPrimitive(42),
                    "decimal" to JsonPrimitive(1.25), "label" to JsonPrimitive("premium")
                )),
                PushSubscription(permission = "future_permission"),
                revision = 9007199254740991L,
                updatedAt = "2026-09-24T00:00:00Z"
            )
            server.enqueue(MockResponse().setBody(wireJson.encodeToString(expected)))

            assertEquals(expected, HttpTransport(config(server)).read("installation", "credential"))
        }
    }

    @Test
    fun patchesDistinguishOmittedRemovedReplacedAndPreferenceOnlyEmail() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(config(server))
            val patches = listOf(
                UserChanges(tags = mapOf("keep" to TagChange(1, JsonPrimitive(true)), "remove" to TagChange(1, null))) to
                    """{"associationId":"association","tags":{"keep":true},"removeTags":["remove"]}""",
                UserChanges(email = EmailChange(1, remove = true)) to
                    """{"associationId":"association","clearEmail":true}""",
                UserChanges(email = EmailChange(1, optedIn = false)) to
                    """{"associationId":"association","email":{"optedIn":false}}""",
                UserChanges(email = EmailChange(1, address = "a@example.com", optedIn = true)) to
                    """{"associationId":"association","email":{"address":"a@example.com","optedIn":true}}"""
            )

            for ((index, patch) in patches.withIndex()) {
                server.replyState()
                transport.updateUser("installation", "credential", UserRequest("patch$index", "association", patch.first))
                assertEquals(
                    wireJson.parseToJsonElement(patch.second),
                    server.next("PATCH", "/v1/sdk/installations/installation/user", "patch$index").bodyJson()
                )
            }

            val json = kotlinx.serialization.json.Json(wireJson) { encodeDefaults = false }
            val absent = json.encodeToJsonElement(UserPatch("association")).jsonObject
            val removed = json.encodeToJsonElement(UserPatch("association", clearEmail = true)).jsonObject
            assertFalse(absent.containsKey("email"))
            assertEquals(JsonPrimitive(true), removed["clearEmail"])

        }
    }

    @Test
    fun nullableTokenIsSentAndEventVariantsHaveTheirOwnTypedPayloads() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(config(server))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            transport.push("installation", "credential", PushRequest("push", "association", DeviceProperties(permission = "denied")))
            assertEquals(
                wireJson.parseToJsonElement("""{"associationId":"association","provider":"fcm","token":null,"permission":"denied","optedIn":true,"metadata":{}}"""),
                server.next("PUT", "/v1/sdk/installations/installation/subscription", "push").bodyJson()
            )

            val properties = wireJson.parseToJsonElement("""{"nested":[true,null,{"number":1.25}]}""").jsonObject
            val events = listOf(
                Event.Track("purchase", properties, 1900000000000),
                Event.Interaction("delivery", "received", 1900000000001),
                Event.Interaction("delivery", "opened", 1900000000002)
            )

            for ((index, event) in events.withIndex()) {
                server.enqueue(MockResponse().setBody("""{"results":[{"operationId":"event$index","succeeded":true}]}"""))
                transport.sendEvent("installation", "credential", QueuedEvent("event$index", "association", event))
                val body = server.next("POST", "/v1/sdk/installations/installation/operations", "event$index").bodyJson()
                assertEquals(JsonPrimitive("association"), body["associationId"])
                val operation = body.getValue("operations").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("event$index"), operation["operationId"])
                val payload = operation.getValue(if (event is Event.Track) "event" else "interaction").jsonObject

                when (event) {
                    is Event.Track -> {
                        assertEquals(setOf("operationId", "event"), operation.keys)
                        assertEquals(properties, payload["properties"])
                        assertEquals(JsonPrimitive(event.occurredAt), payload["occurredAt"])
                        assertEquals(JsonPrimitive("event$index"), payload["eventId"])
                        assertEquals(JsonPrimitive("purchase"), payload["name"])
                    }
                    is Event.Interaction -> {
                        assertEquals(setOf("operationId", "interaction"), operation.keys)
                        assertEquals(JsonPrimitive(event.kind), payload["type"])
                        assertEquals(JsonPrimitive(event.occurredAt), payload["occurredAt"])
                        assertEquals(JsonPrimitive("delivery"), payload["deliveryId"])
                    }
                }
            }
        }
    }

    @Test
    fun basePrefixAndPathParametersAreEncoded() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(config(server, "/prefix/"))
            server.replyState()
            transport.read("id/with ?", "credential")
            server.next("GET", "/prefix/v1/sdk/installations/id%2Fwith%20%3F", null)
        }
    }

    private suspend fun fails(code: String, retryable: Boolean, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (error: TransportException) {
            assertEquals(code, error.code)
            assertEquals(retryable, error.retryable)
        }
    }

    @Test
    fun typedResponseFailuresAreClassifiedWithoutLeakingServerText() = runTest {
        MockWebServer().use { server ->
            val transport = HttpTransport(config(server))

            for (body in listOf("not json", "{}", "")) {
                server.enqueue(MockResponse().setBody(body))
                fails("INVALID_RESPONSE", false) { transport.read("installation", "credential") }
            }

            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            fails("RESPONSE_TOO_LARGE", false) { transport.read("installation", "credential") }

            for (status in listOf(400, 401, 409, 408, 429, 500, 503)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "12").setBody("private server message"))
                fails("HTTP_$status", status in listOf(408, 429, 500, 503)) { transport.read("installation", "credential") }
            }

            for (body in listOf(
                """{"results":[]}""",
                """{"results":[{"operationId":"wrong","succeeded":true}]}""",
                """{"results":[{"operationId":"event"}]}"""
            )) {
                server.enqueue(MockResponse().setBody(body))
                fails("INVALID_RESPONSE", false) {
                    transport.sendEvent("installation", "credential", QueuedEvent("event", "association", Event.Track("test", JsonObject(emptyMap()), 0)))
                }
            }
        }
    }

    @Test
    fun connectionFailuresAndRedirectsPreserveHttpPolicy() = runTest {
        MockWebServer().use { server ->
            val client = io.ktor.client.engine.okhttp.OkHttp.create { config { retryOnConnectionFailure(false) } }
            val transport = HttpTransport(config(server), client = client)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fails("NETWORK", true) { transport.read("installation", "credential") }
        }

        MockWebServer().use { server ->
            val transport = HttpTransport(config(server))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirected")))
            fails("HTTP_302", false) { transport.read("installation", "credential") }
            assertEquals(1, server.requestCount)
        }

        assertEquals(120000L, retryAfterMillis("Thu, 24 Sep 2026 12:02:00 GMT", 1790251200000))
    }
}
