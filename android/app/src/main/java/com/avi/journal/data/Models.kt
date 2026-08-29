package com.avi.journal.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One day's entry.
 *
 * The shape is dictated by the web app: both clients read and write the SAME
 * Firestore documents at users/{uid}/journalDays/{yyyy-MM-dd}, so this class has
 * to be a faithful superset of what the browser writes, and has to preserve
 * fields it doesn't itself render.
 *
 * Mood is the one place the two diverge. The web app stores a single [mood] in
 * 1..5; this app stores a two-axis [pleasantness] and [energy]. Rather than
 * migrate the web app in the same breath (and break it for anyone on an older
 * cached build), pleasantness is mirrored into `mood` on every write. The
 * browser keeps working unchanged and simply doesn't show the energy axis, and
 * this app falls back to `mood` when it finds a day the browser wrote.
 */
data class Entry(
    val date: LocalDate,
    val title: String = "",
    val plain: String = "",
    /**
     * The web app's rich-text body. Preserved verbatim unless the text is
     * edited here — otherwise opening a browser-written entry on the phone and
     * closing it again would silently flatten its formatting and lose it.
     */
    val html: String = "",
    val pleasantness: Float? = null,
    val energy: Float? = null,
    val place: String? = null,
    val tags: List<String> = emptyList(),
    /** Base64 data URLs written by the web app. Preserved, not editable here. */
    val photos: List<String> = emptyList(),
    val favorite: Boolean = false,
    val words: Int = 0,
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = title.isBlank() && plain.isBlank() && photos.isEmpty() &&
            pleasantness == null && tags.isEmpty() && place.isNullOrBlank()

    /** The legacy 1..5 value the web app reads. Null when no mood was logged. */
    val legacyMood: Int? get() = pleasantness?.let { Math.round(it).coerceIn(1, 5) }

    fun toMap(): Map<String, Any?> = buildMap {
        put(FIELD_DATE, date.format(KEY_FORMAT))
        put(FIELD_TITLE, title)
        put(FIELD_HTML, html)
        put(FIELD_PLAIN, plain)
        put(FIELD_MOOD, legacyMood)
        put(FIELD_PLEASANTNESS, pleasantness?.toDouble())
        put(FIELD_ENERGY, energy?.toDouble())
        put(FIELD_PLACE, place)
        put(FIELD_TAGS, tags)
        put(FIELD_PHOTOS, photos)
        put(FIELD_FAVORITE, favorite)
        put(FIELD_WORDS, words)
        put(FIELD_UPDATED_AT, updatedAt)
    }

    companion object {
        val KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        const val FIELD_DATE = "date"
        const val FIELD_TITLE = "title"
        const val FIELD_HTML = "html"
        const val FIELD_PLAIN = "plain"
        const val FIELD_MOOD = "mood"
        const val FIELD_PLEASANTNESS = "pleasantness"
        const val FIELD_ENERGY = "energy"
        const val FIELD_PLACE = "place"
        const val FIELD_TAGS = "tags"
        const val FIELD_PHOTOS = "photos"
        const val FIELD_FAVORITE = "favorite"
        const val FIELD_WORDS = "words"
        const val FIELD_UPDATED_AT = "updatedAt"

        fun key(date: LocalDate): String = date.format(KEY_FORMAT)

        /**
         * Rebuilds an entry from a Firestore document.
         *
         * Every field is defensive: the web app has been through three storage
         * formats, and documents written by older builds are still out there.
         * A document whose date can't be parsed is dropped rather than defaulted
         * to today, which would silently overwrite a real day.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(documentId: String, data: Map<String, Any?>?): Entry? {
            if (data == null) return null

            val date = parseDate(data[FIELD_DATE] as? String)
                ?: parseDate(documentId)
                ?: return null

            val html = data[FIELD_HTML] as? String ?: ""
            val plain = (data[FIELD_PLAIN] as? String)?.takeIf { it.isNotBlank() }
                ?: htmlToText(html)

            // Prefer the two-axis value; fall back to whatever the browser wrote.
            val legacy = (data[FIELD_MOOD] as? Number)?.toFloat()?.takeIf { it in 1f..5f }
            val pleasantness = (data[FIELD_PLEASANTNESS] as? Number)?.toFloat()
                ?.takeIf { it in 1f..5f } ?: legacy

            return Entry(
                date = date,
                title = data[FIELD_TITLE] as? String ?: "",
                plain = plain,
                html = html,
                pleasantness = pleasantness,
                energy = (data[FIELD_ENERGY] as? Number)?.toFloat()?.takeIf { it in 1f..5f },
                place = (data[FIELD_PLACE] as? String)?.takeIf { it.isNotBlank() },
                tags = (data[FIELD_TAGS] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                photos = (data[FIELD_PHOTOS] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                favorite = data[FIELD_FAVORITE] as? Boolean ?: false,
                words = (data[FIELD_WORDS] as? Number)?.toInt() ?: countWords(plain),
                updatedAt = (data[FIELD_UPDATED_AT] as? Number)?.toLong() ?: 0L,
            )
        }

        private fun parseDate(value: String?): LocalDate? =
            value?.let { runCatching { LocalDate.parse(it, KEY_FORMAT) }.getOrNull() }
    }
}

fun countWords(text: String): Int =
    text.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }

/**
 * Flattens the web app's rich text for display and word counts.
 *
 * Block-level tags become newlines before the rest are stripped, so paragraphs
 * don't run together into one wall of text. This is not a general HTML parser
 * and doesn't need to be — it only ever sees markup this app's own editor
 * produced.
 */
fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote)>"), "\n\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Wraps edited plain text back into the paragraph markup the web app expects. */
fun textToHtml(text: String): String =
    text.split(Regex("\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { paragraph ->
            val escaped = paragraph
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")
            "<p>$escaped</p>"
        }

/** Labels for the pleasantness axis, matching the web app's five steps. */
fun pleasantnessLabel(value: Float): String = when {
    value < 1.5f -> "Rough"
    value < 2.5f -> "Low"
    value < 3.5f -> "Okay"
    value < 4.5f -> "Good"
    else -> "Great"
}

/** Labels for the energy axis. */
fun energyLabel(value: Float): String = when {
    value < 1.5f -> "Drained"
    value < 2.5f -> "Tired"
    value < 3.5f -> "Steady"
    value < 4.5f -> "Lively"
    else -> "Wired"
}

/**
 * The phrase shown under the mood pad.
 *
 * Two independent axes give 25 combinations, and naming each one separately
 * would be both a lot of copy and a lot of ways to be slightly wrong about
 * someone's inner state. Combining the two labels keeps it honest.
 */
fun moodPhrase(pleasantness: Float, energy: Float): String =
    "${pleasantnessLabel(pleasantness)} · ${energyLabel(energy)}"

/**
 * Appearance preference. Same three states and the same cycling affordance as
 * WeightTracker, so the two apps' settings screens behave identically.
 */
enum class ThemeChoice(val label: String) {
    SYSTEM("Auto"),
    LIGHT("Light"),
    DARK("Dark");

    fun next(): ThemeChoice = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}
