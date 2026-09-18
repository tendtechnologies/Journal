package com.avi.journal.data

import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    private val today = LocalDate.of(2026, 9, 17) // a Thursday

    // ── Streaks ──────────────────────────────────────────────────────────

    @Test
    fun `empty journal has no streak`() {
        assertEquals(Streaks(0, 0), Insights.streaks(emptySet(), today))
    }

    @Test
    fun `today counts toward the current streak`() {
        val dates = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, Insights.streaks(dates, today).current)
        assertEquals(3, Insights.streaks(dates, today).longest)
    }

    @Test
    fun `an unwritten today does not break the streak`() {
        val dates = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, Insights.streaks(dates, today).current)
    }

    @Test
    fun `gap breaks the current streak but longest survives`() {
        val dates = setOf(today, today.minusDays(1), today.minusDays(5), today.minusDays(6))
        val streaks = Insights.streaks(dates, today)
        assertEquals(2, streaks.current)
        assertEquals(2, streaks.longest)
    }

    @Test
    fun `longest streak spans a gap in the middle`() {
        val dates = (0L..6L).map { today.minusDays(it) }.toSet() + // 7 in a row
            setOf(today.minusDays(8), today.minusDays(9))
        val streaks = Insights.streaks(dates, today)
        assertEquals(7, streaks.longest)
        assertEquals(7, streaks.current)
    }

    // ── Weekday rates ────────────────────────────────────────────────────

    @Test
    fun `weekday rate divides by actual occurrences`() {
        // Journal started Monday this week; two of the three weekdays
        // (Mon-Wed) occurred before and including today.
        val monday = today.minusDays(3)
        val dates = setOf(monday, today) // Monday and Thursday
        val rates = Insights.weekdayRates(dates, today)
        val mon = rates.first { it.dayName == "Monday" }
        val thu = rates.first { it.dayName == "Thursday" }
        assertEquals(1, mon.written)
        assertEquals(1, mon.occurrences)
        assertEquals(1f, mon.rate, 0.001f)
        assertEquals(1, thu.occurrences)
    }

    @Test
    fun `weekday rates empty input yields zero rates`() {
        val rates = Insights.weekdayRates(emptySet(), today)
        assertEquals(7, rates.size)
        assertTrue(rates.all { it.occurrences == 0 && it.rate == 0f })
    }

    // ── Sleep vs mood ────────────────────────────────────────────────────

    private fun entriesWithMood(vararg moods: Pair<LocalDate, Float>) =
        moods.map { (d, m) -> Entry(date = d, pleasantness = m) }

    private fun healthWithSleep(vararg sleeps: Pair<LocalDate, Double>) =
        sleeps.associate { (d, h) -> d to DayHealth(date = d, sleep = Duration.ofMinutes((h * 60).toLong())) }

    @Test
    fun `sleep finding needs the minimum sample`() {
        val entries = entriesWithMood(
            today.minusDays(1) to 4f, today.minusDays(2) to 3f,
            today.minusDays(3) to 4f, today.minusDays(4) to 2f,
        )
        val health = healthWithSleep(
            today.minusDays(1) to 8.0, today.minusDays(2) to 5.0,
            today.minusDays(3) to 8.0, today.minusDays(4) to 5.0,
        )
        assertNull(Insights.sleepVsMood(entries, health))
    }

    @Test
    fun `well slept days rate higher`() {
        val entries = entriesWithMood(
            *(1L..5L).map { today.minusDays(it) to 4.5f }.toTypedArray(),
            *(6L..10L).map { today.minusDays(it) to 2f }.toTypedArray(),
        )
        val health = healthWithSleep(
            *(1L..5L).map { today.minusDays(it) to 8.0 }.toTypedArray(),
            *(6L..10L).map { today.minusDays(it) to 5.0 }.toTypedArray(),
        )
        val finding = Insights.sleepVsMood(entries, health)
        assertNotNull(finding)
        assertEquals("You rate days better after 7h+ sleep", finding!!.headline)
        assertEquals(10, finding.sample)
    }

    @Test
    fun `days until findings counts down to the threshold`() {
        val entries = entriesWithMood(
            today.minusDays(1) to 4f, today.minusDays(2) to 3f,
        )
        val health = healthWithSleep(today.minusDays(1) to 8.0, today.minusDays(2) to 6.0)
        assertEquals(Insights.MIN_SAMPLE - 2, Insights.daysUntilFindings(entries, health))
        // Nothing paired yet: the full threshold is still ahead.
        assertEquals(Insights.MIN_SAMPLE, Insights.daysUntilFindings(emptyList(), emptyMap()))
        // Ten paired days: findings are available now.
        val enough = entriesWithMood(*(1L..10L).map { today.minusDays(it) to 3f }.toTypedArray())
        val enoughHealth = healthWithSleep(*(1L..10L).map { today.minusDays(it) to 7.0 }.toTypedArray())
        assertEquals(0, Insights.daysUntilFindings(enough, enoughHealth))
    }
}
