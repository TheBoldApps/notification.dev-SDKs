package dev.notification.sdk

import androidx.core.util.AtomicFile
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStateStoreTest {
    @Test
    fun configurationPassesHostedDefaultAndOverridesToCore() {
        val hosted = SdkConfig(projectId = "project", smallIcon = 1)
        val local = hosted.copy(baseUrl = "http://localhost:3000/", allowLocalhostHttp = true)

        assertEquals("https://app.notification.dev/", hosted.coreConfig().baseUrl)
        assertEquals("http://localhost:3000/", local.coreConfig().baseUrl)
        assertTrue(local.coreConfig().allowLocalhostHttp)
    }


    @get:Rule
    val temporary = TemporaryFolder()

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptedStateRoundTripsAcrossStoreInstances() = runTest {
        val file = File(temporary.root, "state")
        val key = key()
        val state = """{"installationId":"installation","credential":"secret","email":"a@example.com","retry":12345}"""
        val store = FileStateStore(file, key)
        assertNull(store.load())
        store.save(state)
        assertFalse(file.readBytes().decodeToString().contains("a@example.com"))
        assertEquals(state, FileStateStore(file, key).load())

        val first = file.readBytes()
        store.save(state)
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(state, FileStateStore(file, key).load())
    }

    @Test fun interruptedReplacementRetainsPreviousCompleteState() = runTest {
        val file = File(temporary.root, "state")
        val key = key()
        val original = "installation-secret-state"
        FileStateStore(file, key).save(original)
        // Simulate a process dying after writing a partial replacement but before commit.
        AtomicFile(file).startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(original, FileStateStore(file, key).load())
    }

    @Test fun corruptOrWrongKeyStateFailsInsteadOfSilentlyRegisteringANewInstallation() = runTest {
        val file = File(temporary.root, "state")
        val key = key()
        val store = FileStateStore(file, key)
        store.save("installation-secret-state")
        try {
            FileStateStore(file, key()).load()
            fail("Expected authentication failure")
        } catch (_: java.security.GeneralSecurityException) { }
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            store.load()
            fail("Expected authentication failure")
        } catch (_: java.security.GeneralSecurityException) { }
        file.writeBytes(byteArrayOf(1))
        try {
            store.load()
            fail("Expected truncated state rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
