package dev.notification.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FileStateStore private constructor(
    private val file: AtomicFile,
    keyProvider: () -> SecretKey
) : SecureStorage {
    private val key by lazy(keyProvider)

    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, "notification-sdk-kmp.state")),
        ::storageKey
    )

    // Real file I/O and encryption can be tested without an Android Keystore.
    internal constructor(file: File, key: SecretKey) : this(AtomicFile(file), { key })

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        val bytes = try {
            file.readFully()
        } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                throw e
            }

            return@withContext null
        }
        require(bytes.size >= 12 + 16) { "Invalid encrypted SDK state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))

        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }

    override suspend fun save(state: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.iv + cipher.doFinal(state.encodeToByteArray())
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

private fun storageKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    return (store.getKey("notification.dev.sdk.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                "notification.dev.sdk.v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
    }.generateKey()
}
