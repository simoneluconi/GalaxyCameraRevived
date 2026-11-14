// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gctransfer

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
    @android.annotation.SuppressLint("ApplySharedPref")
    fun setPassword(password: String?) = synchronized(LOCK) {
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

    /**
     * A new browser session (the cookie's token) when [password] is right, else null. Check and
     * token in one step: a login must not pass against the old password and keep its token
     * after a change.
     */
    fun login(password: String): String? {
        // PBKDF2 outside the lock, so other browsers' requests do not wait for it…
        val (saltHex, hashHex) = synchronized(LOCK) {
            (prefs.getString(KEY_SALT, null) ?: return null) to (prefs.getString(KEY_HASH, null) ?: return null)
        }
        if (!MessageDigest.isEqual(pbkdf2(password, unhex(saltHex)), unhex(hashHex))) return null
        // …and the token only if the password is still the one just checked
        return synchronized(LOCK) { if (prefs.getString(KEY_HASH, null) == hashHex) newToken() else null }
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

    /** The cookie carries the token, this keeps its hash. Under [LOCK]. */
    private fun newToken(): String {
        val token = hex(ByteArray(24).also { random.nextBytes(it) })
        val tokens = (prefs.getStringSet(KEY_TOKENS, null) ?: emptySet()).toMutableSet()
        // A few browsers at most: drop the oldest-looking surplus rather than grow forever
        while (tokens.size >= MAX_TOKENS) tokens.remove(tokens.first())
        tokens.add(sha256(token))
        prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
        return token
    }

    fun valid(token: String): Boolean = synchronized(LOCK) { prefs.getStringSet(KEY_TOKENS, null)?.contains(sha256(token)) == true }

    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private companion object {
        /** One for the process: the server and the screen each hold their own TransferAuth. */
        val LOCK = Any()
        val HEX = "0123456789abcdef".toCharArray()
        const val KEY_HASH = "password_hash"
        const val KEY_SALT = "password_salt"
        const val KEY_TOKENS = "tokens"
        const val MAX_TOKENS = 16
        /** PBKDF2 rounds: slow enough against guessing, still quick for one login on this CPU. */
        const val ITERATIONS = 20_000
    }
}
