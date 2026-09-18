package com.avi.journal.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry.fromMap/toMap round-trips — the contract both clients share at
 * users/{uid}/journalDays/{date}. This is exactly where cross-client drift
 * bugs live: a field one side forgets to parse or write silently corrupts
 * the other side's data.
 */
class EntryRoundTripTest {

    private val date = LocalDate.of(2026, 9, 17)

    @Test
    fun `full entry survives a round trip`() {
        val entry = Entry(
            date = date,
            title = "A day",
            plain = "Some words",
            html = "<p>Some words</p>",
            pleasantness = 4f,
            energy = 2f,
            place = "Lisbon",
            tags = listOf("travel", "food"),
            photos = listOf("https://firebasestorage.example/pic.jpg", "data:image/jpeg;base64,AAAA"),
            favorite = true,
            words = 2,
            updatedAt = 1726588800000L,
        )

        val rebuilt = Entry.fromMap(Entry.key(date), entry.toMap())

        assertEquals(entry, rebuilt)
    }

    @Test
    fun `tombstone survives a round trip`() {
        val trashed = Entry(
            date = date,
            title = "oops",
            updatedAt = 1726588800000L,
            deleted = true,
            deletedAt = 1726590000000L,
        )

        val rebuilt = Entry.fromMap(Entry.key(date), trashed.toMap())

        assertTrue(rebuilt!!.deleted)
        assertEquals(1726590000000L, rebuilt.deletedAt)
    }

    @Test
    fun `tombstone fields are parsed from a web-written document`() {
        // Shape the web app actually writes for a trashed day.
        val webDoc = mapOf(
            "date" to "2026-09-17",
            "title" to "deleted from the browser",
            "html" to "<p>deleted from the browser</p>",
            "plain" to "deleted from the browser",
            "mood" to 3L,
            "tags" to emptyList<String>(),
            "photos" to emptyList<String>(),
            "favorite" to false,
            "words" to 3L,
            "updatedAt" to 1726588800000L,
            "deleted" to true,
            "deletedAt" to 1726590000000L,
        )

        val entry = Entry.fromMap("2026-09-17", webDoc)

        assertTrue(entry!!.deleted)
        assertEquals(1726590000000L, entry.deletedAt)
        // Fields the phone doesn't render must still be preserved.
        assertEquals("deleted from the browser", entry.title)
    }

    @Test
    fun `live entry round trip leaves tombstone unset`() {
        val rebuilt = Entry.fromMap(Entry.key(date), Entry(date = date).toMap())
        assertFalse(rebuilt!!.deleted)
        assertNull(rebuilt.deletedAt)
    }

    @Test
    fun `document with unparseable date is dropped`() {
        assertNull(Entry.fromMap("not-a-date", mapOf("title" to "x")))
        assertNull(Entry.fromMap("2026-13-40", mapOf("title" to "x")))
    }

    @Test
    fun `plain falls back to flattened html`() {
        val entry = Entry.fromMap(
            Entry.key(date),
            mapOf("html" to "<p>Hello</p><p>World</p>"),
        )
        assertEquals("Hello\n\nWorld", entry!!.plain)
    }

    @Test
    fun `pleasantness falls back to legacy mood but rejects out of range`() {
        val fromLegacy = Entry.fromMap(Entry.key(date), mapOf("mood" to 5L))
        assertEquals(5f, fromLegacy!!.pleasantness)

        val outOfRange = Entry.fromMap(Entry.key(date), mapOf("mood" to 9L))
        assertNull(outOfRange!!.pleasantness)
    }
}
