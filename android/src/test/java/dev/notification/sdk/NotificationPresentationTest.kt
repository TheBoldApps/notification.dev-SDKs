package dev.notification.sdk

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NotificationPresentationTest {
    private val payload = NotificationPayload(deliveryId = "first", associationId = "account",
        expiresAt = 100, title = "Title", body = "Body")

    @Test fun replacementUsesCollapseAndAssociationWithoutChangingDeliveryIdentity() {
        assertNotEquals(notificationTag(payload), notificationTag(payload.copy(deliveryId = "second")))
        val collapsed = payload.copy(collapseId = "chat")
        assertEquals(notificationTag(collapsed), notificationTag(collapsed.copy(deliveryId = "second")))
        assertNotEquals(notificationTag(collapsed), notificationTag(collapsed.copy(associationId = "other")))
        assertEquals("first", collapsed.deliveryId)
    }

    @Test fun imageReadsAreBoundedBySizeAndDeadline() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        assertArrayEquals(byteArrayOf(1, 2), readImageBytes(ByteArrayInputStream(byteArrayOf(1, 2)), deadline))
        assertThrows(IllegalStateException::class.java) {
            readImageBytes(ByteArrayInputStream(ByteArray(MAX_IMAGE_BYTES + 1)), deadline)
        }
        assertThrows(IllegalStateException::class.java) {
            readImageBytes(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }
}
