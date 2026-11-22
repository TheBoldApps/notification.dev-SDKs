package dev.notification.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommunicationGroupTest {
    @Test fun conversationsAreStableAndScopedToTheCurrentAssociation() {
        val first = communicationGroup("association-a", "private-chat-id")
        assertEquals(first, communicationGroup("association-a", "private-chat-id"))
        assertNotEquals(first, communicationGroup("association-b", "private-chat-id"))
        assertNotEquals(first, communicationGroup("association-a", "another-chat"))
        assertFalse(first.contains("private-chat-id"))
    }
}
