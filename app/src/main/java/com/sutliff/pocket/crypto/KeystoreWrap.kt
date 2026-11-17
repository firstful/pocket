package com.sutliff.pocket.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when a Keystore secret cannot be wrapped or unwrapped.
 *
 * The most common cause is the AES key being invalidated by a device security
 * change (new lock credential, FRP reset, StrongBox/TEE reset). In that case
 * [KeyStore.getKey] can throw `KeyStoreException` and cipher init can throw
 * `InvalidKeyException` / `AEADBadTagException`. Those values are NOT
 * recoverable on this device: the user must re-import config from Settings
 * (the passphrase endpoint re-issues the secrets) so the keys are re-wrapped
 * under the new hardware key.
 */
class SecretWrapException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Hardware-backed AES-GCM wrapping for small on-device secrets (API keys).
 *
 * Design constraints:
 *  - The key lives in the AndroidKeyStore, is generated with
 *    `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, and is NON-exportable by design
 *    (symmetric AndroidKeyStore keys can never leave secure hardware).
 *  - `setUserAuthenticationRequired(false)`: Pocket is HEADLESS (SMS / boot /
 *    notification triggers read secrets with no Activity to host a
 *    BiometricPrompt). A user-auth gate would throw on background reads and
 *    break sessions. There is deliberately no BiometricPrompt dependency.
 *  - Wrapped values are `enc:v1:<base64(iv||ciphertext)>`. A 12-byte IV is
 *    generated per wrap with SecureRandom; the 128-bit GCM tag is appended by
 *    the cipher to the ciphertext.
 *  - Values WITHOUT the prefix are returned unchanged. That is intentional
 *    backward compatibility for legacy plaintext written by seeded builds or
 *    manually entered in Settings, so existing installs keep working.
 *  - `wrap` is idempotent: an already-prefixed value is passed through rather
 *    than wrapped twice (the prefix is treated as a reserved marker).
 */
object KeystoreWrap {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pocket_secret_wrap_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun wrap(plain: String): String {
        // Blank = "no key" (the common default) — leave it blank so headless
        // reads never need Keystore for an unset key. Already-wrapped = pass
        // through so a save of a value that came back from a read cannot
        // double-wrap it.
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return try {
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            val blob = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw wrapFailure("wrap", e)
        }
    }

    fun unwrap(stored: String): String {
        // Legacy plaintext (seeded builds / manual Settings) reads unchanged.
        if (!stored.startsWith(PREFIX)) return stored
        val encoded = stored.substring(PREFIX.length)
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw wrapFailure("decode", e)
        }
        if (blob.size <= IV_BYTES) {
            throw wrapFailure("decode", IllegalArgumentException("wrapped value is too short"))
        }
        return try {
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Covers KeyStoreException / InvalidKeyException (key invalidated)
            // and AEADBadTagException (tampered / wrong key).
            throw wrapFailure("unwrap", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            // StrongBox = dedicated secure hardware when the device has it.
            // Not all devices do (generateKey throws StrongBoxUnavailableException
            // there), so fall back to the platform AndroidKeyStore, which is
            // still TEE-backed where supported.
            try {
                createKey(strongBox = true)
            } catch (e: Exception) {
                createKey(strongBox = false)
            }
        } catch (e: Exception) {
            throw wrapFailure("key access", e)
        }
    }

    private fun createKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Headless app: NEVER gate on user authentication (no BiometricPrompt).
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setIsStrongBoxBacked(true)
        }
        // Symmetric AndroidKeyStore keys are non-exportable by platform design.
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(spec.build())
        return keyGenerator.generateKey()
    }

    private fun wrapFailure(operation: String, cause: Throwable): SecretWrapException = SecretWrapException(
        "Keystore secret $operation failed — the hardware key may have been invalidated " +
            "by a device security change. Re-import config from Settings to restore secrets.",
        cause
    )
}
