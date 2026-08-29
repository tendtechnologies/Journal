package com.avi.journal.data

import java.security.MessageDigest

/**
 * Verifies the PIN the web app set.
 *
 * The scheme has to match the browser's exactly — SHA-256 over the literal
 * string "salt:pin", hex-encoded lowercase — because both clients read the same
 * record at users/{uid}/settings/security.
 *
 * This app only verifies; it never sets or clears a PIN. Setting one stays in
 * the browser so there is a single place that owns the format.
 *
 * Worth being plain about what this is: a screen lock, not encryption. Entries
 * are stored unencrypted, so anyone with the Google account can read them
 * directly. It stops someone picking up an unlocked phone, and nothing more.
 */
object PinLock {

    fun verify(pin: String, lock: LockState.Pin): Boolean =
        sha256Hex("${lock.salt}:$pin") == lock.hash.lowercase()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
