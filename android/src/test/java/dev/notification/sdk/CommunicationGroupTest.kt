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
    @Test fun shortcutIdsAreOpaqueAssociationScopedAndUseOnlyOurNamespace() {
        val first = communicationShortcutId("a", "private-chat")
        assertEquals(first, communicationShortcutId("a", "private-chat"))
        assertNotEquals(first, communicationShortcutId("b", "private-chat"))
        assertNotEquals(communicationShortcutId("a", "bc"), communicationShortcutId("ab", "c"))
        assertFalse(first.contains("private-chat"))
        org.junit.Assert.assertTrue(first.startsWith(COMMUNICATION_SHORTCUT_PREFIX))
    }

    @Test fun conversationDestinationsRejectUnsafeOrAmbiguousRouting() {
        for (url in listOf("my-app://chat/123", "https://example.com/chat/123")) {
            org.junit.Assert.assertTrue(url, isSafeConversationDestination(url))
        }
        for (url in listOf("chat/123", "javascript:alert(1)", "intent://chat", "file:///secret", "content://secret", "https://user:pass@example.com/chat", "https://", "mailto:person@example.com", "my-app://chat/\n")) {
            assertFalse(url, isSafeConversationDestination(url))
        }
    }
    @Test fun personKeysKeepCommonSenderIdsScopedToTheirAssociation() {
        val first = communicationPersonKey("account-a", "1")
        assertEquals(first, communicationPersonKey("account-a", "1"))
        assertNotEquals(first, communicationPersonKey("account-b", "1"))
        assertNotEquals(first, communicationPersonKey("account-a", "2"))
        assertNotEquals(first, communicationShortcutId("account-a", "1"))
        assertEquals("dev.notification.sdk.person.".length + 64, first.length)
        assertFalse(first.contains("account-a"))
    }
}
