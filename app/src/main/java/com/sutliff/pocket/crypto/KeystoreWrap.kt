package com.sutliff.pocket.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when a Keystore secret cannot be wrapped or unwrapped.
 *
 * The message now includes the REAL exception class + message (previously the
 * cause was attached but never surfaced, which made diagnosis impossible).
 */
class SecretWrapException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Hardware-backed AES-GCM wrapping for small on-device secrets (API keys).
 *
 * Design constraints (unchanged):
 *  - Non-exportable symmetric AndroidKeyStore key, StrongBox/TEE where present.
 *  - `setUserAuthenticationRequired(false)` — Pocket is headless (SMS/boot/
 *    notification triggers read secrets with no Activity for BiometricPrompt).
 *  - Wrapped values are `enc:v1:<base64(iv||ciphertext)>`, 12-byte IV + 128-bit
 *    GCM tag, fresh IV per wrap.
 *  - Values WITHOUT the prefix are returned unchanged (legacy plaintext compat:
 *    seeded builds / keys typed in Settings still read and keep working).
 *  - `wrap` is idempotent: already-wrapped values pass through.
 *
 * Recovery (NEW):
 *  - An existing key can be invalidated/unusable after a device security
 *    change. If wrapping with it fails, drop the alias and retry once with a
 *    fresh key — wrapping new plaintext is always recoverable, so import heals.
 *  - If UNWRAPPING fails with an invalidated key, the old ciphertext is
 *    unrecoverable; we drop the bad key, install a fresh one so the subsequent
 *    re-import's wrap succeeds, and signal the caller to re-import.
 *  - Every failure is logged with tag PocketKeystore (type + message only,
 *    never the secret) so the real cause is visible in logcat.
 */
object KeystoreWrap {
    private const val TAG = "PocketKeystore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pocket_secret_wrap_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun wrap(plain: String): String {
        // Blank = "no key" (common default) — leave blank so headless reads
        // never need Keystore for an unset key. Already-wrapped = pass through.
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain

        val key = try {
            getOrCreateKey()
        } catch (e: Exception) {
            logFailure("key-gen", e)
            throw wrapFailure("wrap", e)
        }

        return try {
            wrapWith(key, plain)
        } catch (e: Exception) {
            logFailure("wrap", e)
            // Existing key may be invalidated/unusable. Drop and regenerate,
            // then retry once — a fresh key can always wrap new plaintext.
            val fresh = try {
                newKey()
            } catch (e2: Exception) {
                logFailure("regen", e2)
                throw wrapFailure("wrap", e)
            }
            try {
                wrapWith(fresh, plain)
            } catch (e3: Exception) {
                logFailure("wrap-after-regen", e3)
                throw wrapFailure("wrap", e3)
            }
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

        val key = try {
            getOrCreateKey()
        } catch (e: Exception) {
            logFailure("key-access", e)
            throw wrapFailure("unwrap", e)
        }

        return try {
            unwrapWith(key, blob)
        } catch (e: Exception) {
            logFailure("unwrap", e)
            // Old ciphertext cannot be decrypted with a regenerated key. Drop
            // the bad key and install a fresh one so the user's re-import
            // (wrap) works, then signal that a re-import is required.
            try {
                newKey()
            } catch (e2: Exception) {
                logFailure("regen", e2)
            }
            throw wrapFailure("unwrap", e)
        }
    }

    private fun wrapWith(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The key is generated with setRandomizedEncryptionRequired(true), so
        // supplying our own GCMParameterSpec on ENCRYPT throws
        // "caller provided IV not permitted". Let the cipher generate a fresh
        // random IV, then read it back via cipher.iv and prepend it to the
        // ciphertext so unwrap() can reconstruct the GCMParameterSpec.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes for GCM
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = iv + ciphertext
        return PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun unwrapWith(key: SecretKey, blob: ByteArray): String {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return newKey()
    }

    /** Generate a fresh key under the alias, clearing any stale/partial entry. */
    private fun newKey(): SecretKey {
        deleteKey()
        try {
            return createKey(strongBox = true)
        } catch (e: Exception) {
            // StrongBox not available / not usable here — fall back to the
            // platform AndroidKeyStore (still TEE-backed where supported).
            logFailure("strongbox-unavailable", e)
            deleteKey()
            return createKey(strongBox = false)
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

    private fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            logFailure("delete", e)
        }
    }

    /** Log the real exception (type + message only — never secret values). */
    private fun logFailure(operation: String, cause: Throwable) {
        Log.w(TAG, "keystore $operation failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
    }

    private fun wrapFailure(operation: String, cause: Throwable): SecretWrapException =
        SecretWrapException(
            "Keystore secret $operation failed: ${cause.javaClass.simpleName}: ${cause.message}",
            cause
        )
}
