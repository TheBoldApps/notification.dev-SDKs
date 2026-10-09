@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.notification.sdk

import dev.notification.sdk.generated.api.NativeSDKApi
import dev.notification.sdk.generated.infrastructure.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.observer.wrapWithContent
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun httpErrorCode(body: String, status: Int): String {
    val code = runCatching {
        val error = wireJson.parseToJsonElement(body) as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    return code?.takeIf { it.isNotBlank() } ?: "HTTP_$status"
}

internal const val INSTALLATION_PATH_ID = "__notification_installation_id__"

private val requestJson = Json(wireJson) { encodeDefaults = false }

/** One shared engine; each request captures its own authentication and client lifetime. */
internal class ApiHttpClient(
    config: CoreConfig,
    private val engine: HttpClientEngine = platformHttpEngine(),
    private val logger: SdkLogger = SdkLogger()
) {
    private val base = Url(config.baseUrl.trimEnd('/') + "/")

    init {
        require(base.user == null && base.password == null && base.parameters.isEmpty() && base.fragment.isEmpty())
        require(base.protocol == URLProtocol.HTTPS || (config.allowHttp && base.protocol == URLProtocol.HTTP)) {
            "HTTPS is required unless allowHttp is enabled"
        }
    }

    suspend inline fun <reified T : Any> execute(credential: String? = null, pathId: String? = null, call: suspend (NativeSDKApi) -> HttpResponse<T>): T {
        require(pathId != "." && pathId != "..")

        val client = HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(ContentNegotiation) { json(requestJson) }
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
            defaultRequest {
                credential?.let { header("Authorization", "Bearer $it") }
            }
        }

        // The stock generator splits the path before escaping parameters. Substitute a
        // single opaque segment here so slashes in IDs cannot change the endpoint path.
        if (pathId != null) {
            client.requestPipeline.intercept(HttpRequestPipeline.Before) {
                val prefix = base.encodedPath.trimEnd('/')
                val relative = context.url.encodedPath.removePrefix(prefix)
                context.url.encodedPath = prefix + relative.replace(INSTALLATION_PATH_ID, pathId.encodeURLPathPart())
            }
        }

        // Bound the raw stream before Ktor buffers or deserializes it.
        client.receivePipeline.intercept(HttpReceivePipeline.Before) {
            val bytes = subject.rawContent.readRemaining(1024L * 1024 + 1).readByteArray()
            if (bytes.size > 1024 * 1024) {
                subject.rawContent.cancel(TransportException("RESPONSE_TOO_LARGE", false))
                throw TransportException("RESPONSE_TOO_LARGE", false)
            }

            proceedWith(subject.call.wrapWithContent(ByteReadChannel(bytes)).response)
        }

        try {
            logger.debug("HTTP SDK request -> ${base.protocol.name}://${base.host}:${base.port}")
            val response = call(NativeSDKApi(base.toString(), client))
            logger.debug("HTTP SDK response ${response.status}")

            if (!response.success) {
                throw TransportException(
                    httpErrorCode(response.response.bodyAsText(), response.status),
                    response.status == 408 || response.status == 429 || response.status >= 500,
                    retryAfterMillis(response.response.headers["Retry-After"]),
                    httpStatus = response.status
                )
            }

            return requestJson.decodeFromString<T>(response.response.bodyAsText())
        } catch (error: CancellationException) {
            throw error
        } catch (error: TransportException) {
            logger.error("SDK request rejected: ${error.code}; retryable=${error.retryable}")
            throw error
        } catch (error: SerializationException) {
            logger.error("SDK response decoding failed", error)
            throw TransportException("INVALID_RESPONSE", false)
        } catch (error: IOException) {
            logger.error("SDK network request failed")
            throw TransportException("NETWORK", true)
        } finally {
            client.close()
        }
    }

    fun close() = engine.close()
}
