package com.avi.journal.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.LocalDate

/**
 * Entries in Firestore, at users/{uid}/journalDays/{yyyy-MM-dd}.
 *
 * One document per day rather than one document per user. That costs more reads
 * on a full sync, but it is the only shape where two devices editing two
 * different days can never overwrite each other — and this app is explicitly
 * used alongside the web app, so concurrent edits from two clients are the
 * normal case, not the exotic one.
 *
 * The path and field names are fixed by the web app; see Models.kt.
 */
class CloudSync {

    private val db: FirebaseFirestore?
        get() = runCatching { FirebaseFirestore.getInstance() }.getOrNull()

    private fun days(uid: String) =
        (db ?: throw IllegalStateException("Firestore isn't available."))
            .collection(COLLECTION_USERS).document(uid).collection(COLLECTION_DAYS)

    suspend fun pullAll(uid: String): Result<List<Entry>> = runCatching {
        val snapshot = days(uid).get().await()
        // A document that can't be parsed is skipped, not defaulted: a bad date
        // silently becoming "today" would overwrite a real entry. Trashed days
        // (deleted=true tombstones from the web app's trash) are dropped too —
        // they sync so deletions propagate, but never surface in any view.
        snapshot.documents.mapNotNull { Entry.fromMap(it.id, it.data) }
            .filter { !it.deleted }
    }.mapError()

    suspend fun push(uid: String, entry: Entry): Result<Unit> = runCatching {
        days(uid).document(Entry.key(entry.date)).set(entry.toMap()).await()
        Unit
    }.mapError()

    /**
     * HARD delete — permanent removal. Only the trash purge may call this;
     * every user-facing delete is a tombstone (deleted=true), so it can be
     * undone and so other devices learn about it instead of resurrecting
     * the entry on their next sync.
     */
    suspend fun delete(uid: String, date: LocalDate): Result<Unit> = runCatching {
        days(uid).document(Entry.key(date)).delete().await()
        Unit
    }.mapError()

    // ── Lock state ────────────────────────────────────────────────────────
    //
    // Shared with the web app's PIN lock, which stores a salted hash at
    // users/{uid}/settings/security. This app only ever READS it, to know
    // whether to show its own lock screen — the PIN is set from the browser.

    suspend fun readLockState(uid: String): Result<LockState> = runCatching {
        val doc = (db ?: throw IllegalStateException("Firestore isn't available."))
            .collection(COLLECTION_USERS).document(uid)
            .collection("settings").document("security")
            .get().await()

        val data = doc.data
        when {
            data == null -> LockState.None
            data["disabled"] == true -> LockState.None
            data["salt"] is String && data["hash"] is String -> LockState.Pin(
                salt = data["salt"] as String,
                hash = data["hash"] as String,
                length = (data["length"] as? Number)?.toInt() ?: 4,
            )
            else -> LockState.None
        }
    }.mapError()

    /**
     * Firestore's own wording sends people looking in the wrong place.
     *
     * UNAVAILABLE surfaces as "the client is offline" whether the phone really
     * has no signal OR the project simply has no database. PERMISSION_DENIED
     * almost always means the security rules were never published.
     */
    private fun <T> Result<T>.mapError(): Result<T> = recoverCatching { error ->
        val message = when ((error as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "Couldn't reach Firestore. If you're online, check the database has " +
                    "been created in the Firebase console and is named (default)."

            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Firestore refused the request — check the rules allow " +
                    "users/{uid} for the signed-in account."

            FirebaseFirestoreException.Code.NOT_FOUND ->
                "That Firestore database doesn't exist yet. Create it in the Firebase console."

            else -> error.message ?: "Sync failed."
        }
        throw IllegalStateException(message, error)
    }

    private companion object {
        const val COLLECTION_USERS = "users"
        const val COLLECTION_DAYS = "journalDays"
    }
}

sealed interface LockState {
    data object None : LockState
    data class Pin(val salt: String, val hash: String, val length: Int) : LockState
}
