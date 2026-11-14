// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Who may use the transfer page. Open by default (the user's own network); with a password,
 * a browser types it once and keeps a cookie whose token is remembered here, across runs,
 * until the password changes. Only hashes are stored. Thread-safe.
 *
 * The password is set, changed and removed on the camera only, never from the page: whoever
 * holds the camera is the owner, and someone else on the Wi-Fi must not be able to lock them out.
 */
class TransferAuth(context: Context) {

    private val prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val hasPassword: Boolean get() = prefs.contains(KEY_HASH)

    /**
     * Null or empty removes it. Either way the browsers signed in so far must sign in again.
     * Off the main thread: PBKDF2 takes a moment on this CPU.
     */
    @Synchronized
    @android.annotation.SuppressLint("ApplySharedPref")
    fun setPassword(password: String?) {
        val e = prefs.edit().remove(KEY_TOKENS)
        if (password.isNullOrEmpty()) {
            e.remove(KEY_HASH).remove(KEY_SALT)
        } else {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            e.putString(KEY_SALT, hex(salt)).putString(KEY_HASH, hex(pbkdf2(password, salt)))
        }
        // On disk before the screen says so: the old cookies must be gone even after a crash
        e.commit()
    }

    fun check(password: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(::unhex) ?: return false
        val hash = prefs.getString(KEY_HASH, null)?.let(::unhex) ?: return false
        return MessageDigest.isEqual(pbkdf2(password, salt), hash)
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A new browser session; the cookie carries the token, this keeps its hash. */
    @Synchronized
    fun newToken(): String {
        val token = hex(ByteArray(24).also { random.nextBytes(it) })
        val tokens = (prefs.getStringSet(KEY_TOKENS, null) ?: emptySet()).toMutableSet()
        // A few browsers at most: drop the oldest-looking surplus rather than grow forever
        while (tokens.size >= MAX_TOKENS) tokens.remove(tokens.first())
        tokens.add(sha256(token))
        prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
        return token
    }

    fun valid(token: String): Boolean = prefs.getStringSet(KEY_TOKENS, null)?.contains(sha256(token)) == true

    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_HASH = "password_hash"
        const val KEY_SALT = "password_salt"
        const val KEY_TOKENS = "tokens"
        const val MAX_TOKENS = 16
        /** PBKDF2 rounds: slow enough against guessing, still quick for one login on this CPU. */
        const val ITERATIONS = 20_000
    }
}
