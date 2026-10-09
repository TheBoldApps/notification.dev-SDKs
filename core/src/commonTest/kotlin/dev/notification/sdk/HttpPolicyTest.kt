package dev.notification.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HttpPolicyTest {
    private fun validate(url: String, allowHttp: Boolean = false) {
        val engine = MockEngine { respondOk() }
        try {
            ApiHttpClient(CoreConfig("project", url, allowHttp = allowHttp), engine)
        } finally {
            engine.close()
        }
    }

    @Test fun httpsWorksWithEitherFlagValue() {
        validate("https://example.com/")
        validate("https://example.com/", allowHttp = true)
    }

    @Test fun httpRequiresOptInForEveryHost() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.2.20", "10.1.2.3", "172.16.1.2", "[::1]", "example.com")) {
            assertFailsWith<IllegalArgumentException>(host) { validate("http://$host/") }
            validate("http://$host/", allowHttp = true)
        }
    }

    @Test fun otherProtocolsAreRejectedWithEitherFlagValue() {
        for (allowHttp in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> { validate("ftp://example.com/", allowHttp) }
        }
    }
}
