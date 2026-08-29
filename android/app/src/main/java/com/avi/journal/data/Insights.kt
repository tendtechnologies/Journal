package com.avi.journal.data

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

data class Streaks(val current: Int, val longest: Int)

data class WeekdayRate(val dayName: String, val written: Int, val occurrences: Int) {
    val rate: Float get() = if (occurrences == 0) 0f else written.toFloat() / occurrences
}

/**
 * A relationship the app is willing to state out loud.
 *
 * [sample] is carried so the UI can show it. A finding without its sample size
 * invites more confidence than the data supports, and this is exactly the kind
 * of number people screenshot.
 */
data class Finding(
    val headline: String,
    val detail: String,
    val sample: Int,
)

object Insights {

    /**
     * Below this, no correlation is reported at all.
     *
     * Ten paired days is still a small sample — this threshold is about
     * refusing to draw a trend line through four points, not a claim of
     * statistical power. The copy the UI produces is hedged to match.
     */
    const val MIN_SAMPLE = 10

    fun streaks(dates: Set<LocalDate>, today: LocalDate): Streaks {
        if (dates.isEmpty()) return Streaks(0, 0)

        val sorted = dates.sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        sorted.forEach { date ->
            run = if (previous != null && previous!!.plusDays(1) == date) run + 1 else 1
            longest = maxOf(longest, run)
            previous = date
        }

        // A day still in progress shouldn't break the streak, so start counting
        // from yesterday when today hasn't been written yet.
        var cursor = if (dates.contains(today)) today else today.minusDays(1)
        var current = 0
        while (dates.contains(cursor)) {
            current++
            cursor = cursor.minusDays(1)
        }

        return Streaks(current = current, longest = longest)
    }

    /**
     * Share of each weekday actually written on, since the first entry.
     *
     * A raw count per weekday is misleading: a journal begun on a Monday has
     * had more Mondays available than Sundays, so Monday leads the chart purely
     * because of the start date. Dividing by how many of each weekday have
     * actually occurred removes that.
     */
    fun weekdayRates(dates: Set<LocalDate>, today: LocalDate): List<WeekdayRate> {
        val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        if (dates.isEmpty()) return names.map { WeekdayRate(it, 0, 0) }

        val first = dates.min()
        val occurrences = IntArray(7)
        var cursor = first
        while (!cursor.isAfter(today)) {
            occurrences[cursor.dayOfWeek.value - 1]++
            cursor = cursor.plusDays(1)
        }

        val written = IntArray(7)
        dates.forEach { written[it.dayOfWeek.value - 1]++ }

        return names.mapIndexed { index, name ->
            WeekdayRate(dayName = name, written = written[index], occurrences = occurrences[index])
        }
    }

    /**
     * How mood relates to the previous night's sleep.
     *
     * Compares the average pleasantness of well-slept days against
     * short-slept ones, split at seven hours. A difference smaller than a
     * quarter point is reported as "no clear difference" rather than dressed up
     * as a finding — on a five-point scale that is noise.
     */
    fun sleepVsMood(
        entries: Collection<Entry>,
        health: Map<LocalDate, DayHealth>,
    ): Finding? {
        val paired = entries.mapNotNull { entry ->
            val mood = entry.pleasantness ?: return@mapNotNull null
            val hours = health[entry.date]?.sleepHours ?: return@mapNotNull null
            mood to hours
        }
        if (paired.size < MIN_SAMPLE) return null

        val (rested, short) = paired.partition { it.second >= 7.0 }
        if (rested.size < 3 || short.size < 3) return null

        val restedAvg = rested.map { it.first }.average()
        val shortAvg = short.map { it.first }.average()
        val delta = restedAvg - shortAvg

        val headline = if (abs(delta) < 0.25) {
            "Sleep and mood look unrelated"
        } else if (delta > 0) {
            "You rate days better after 7h+ sleep"
        } else {
            "Your better days follow shorter nights"
        }

        val detail = if (abs(delta) < 0.25) {
            "Across ${paired.size} days with both logged, mood was about the same " +
                "either side of seven hours."
        } else {
            "%.1f vs %.1f out of 5, across %d days with both logged."
                .format(restedAvg, shortAvg, paired.size)
        }

        return Finding(headline = headline, detail = detail, sample = paired.size)
    }

    /** The same treatment for steps, split at the person's own median. */
    fun stepsVsMood(
        entries: Collection<Entry>,
        health: Map<LocalDate, DayHealth>,
    ): Finding? {
        val paired = entries.mapNotNull { entry ->
            val mood = entry.pleasantness ?: return@mapNotNull null
            val steps = health[entry.date]?.steps ?: return@mapNotNull null
            mood to steps
        }
        if (paired.size < MIN_SAMPLE) return null

        // The median, not a round 10,000: the useful comparison is against this
        // person's own normal, not a marketing number from a 1960s pedometer.
        val median = paired.map { it.second }.sorted().let { sorted ->
            if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }

        val (active, quiet) = paired.partition { it.second >= median }
        if (active.size < 3 || quiet.size < 3) return null

        val activeAvg = active.map { it.first }.average()
        val quietAvg = quiet.map { it.first }.average()
        val delta = activeAvg - quietAvg

        val headline = when {
            abs(delta) < 0.25 -> "Steps and mood look unrelated"
            delta > 0 -> "Busier days rate slightly better"
            else -> "Quieter days rate slightly better"
        }

        val detail = if (abs(delta) < 0.25) {
            "Across ${paired.size} days with both logged, mood barely moved with step count."
        } else {
            "%.1f vs %.1f out of 5, split at your median of %,d steps (%d days)."
                .format(activeAvg, quietAvg, median.toInt(), paired.size)
        }

        return Finding(headline = headline, detail = detail, sample = paired.size)
    }

    /**
     * How many more days are needed before a correlation is worth showing.
     * The UI says this out loud rather than showing an empty card.
     */
    fun daysUntilFindings(
        entries: Collection<Entry>,
        health: Map<LocalDate, DayHealth>,
    ): Int {
        val paired = entries.count { entry ->
            entry.pleasantness != null && health[entry.date]?.let {
                it.steps != null || it.sleep != null
            } == true
        }
        return (MIN_SAMPLE - paired).coerceAtLeast(0)
    }

    /** Pearson's r, kept for the trend line rather than shown as a number. */
    fun correlation(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 3) return null
        val n = pairs.size
        val meanX = pairs.sumOf { it.first } / n
        val meanY = pairs.sumOf { it.second } / n
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        pairs.forEach { (x, y) ->
            val dx = x - meanX
            val dy = y - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX == 0.0 || varianceY == 0.0) return null
        return covariance / sqrt(varianceX * varianceY)
    }
}
