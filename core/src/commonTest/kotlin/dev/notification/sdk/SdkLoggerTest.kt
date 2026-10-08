package dev.notification.sdk

import kotlin.test.*
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

class SdkLoggerTest {
    @Test fun loggingIsDisabledByDefault() {
        val messages = mutableListOf<String>()
        val logger = SdkLogger(sink = { _, message -> messages.add(message) })
        logger.debug("Starting")
        logger.error("Failed", IOException("Unavailable"))

        assertTrue(messages.isEmpty())
        assertFalse(CoreConfig("project", "https://example.com").loggingEnabled)
    }

    @Test fun enabledLoggingIncludesCauseChainAndStackWithoutSerializationPayloads() {
        val messages = mutableListOf<String>()
        val logger = SdkLogger(true) { _, message -> messages.add(message) }
        val cause = SerializationException("secret response payload")
        logger.debug("Starting")
        logger.error("Failed", IOException("Cannot decode", cause))

        assertEquals("Starting", messages.first())
        assertTrue(messages.last().contains("IOException: Cannot decode"))
        assertTrue(messages.last().contains("SerializationException"))
        assertFalse(messages.last().contains("secret response payload"))
    }
}
