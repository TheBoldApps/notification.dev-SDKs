package dev.notification.sdk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarDownloadTest {
    @Test fun avatarsDoNotFollowRedirectsButOrdinaryAttachmentsRetainTheirBehavior() = runBlocking {
        val targetRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { response ->
            response.responseHeaders.add("Location", "/image")
            response.sendResponseHeaders(302, -1)
            response.close()
        }
        server.createContext("/image") { response ->
            targetRequests.incrementAndGet()
            response.responseHeaders.add("Content-Type", "image/png")
            response.sendResponseHeaders(200, 1)
            response.responseBody.use { it.write(byteArrayOf(0)) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/redirect"
            assertNull(loadNotificationImage(url, allowRedirects = false))
            assertEquals(0, targetRequests.get())
            // Invalid image bytes still fall back; only the HTTP redirect behavior differs.
            assertNull(loadNotificationImage(url))
            assertEquals(1, targetRequests.get())
        } finally {
            server.stop(0)
        }
    }
}
