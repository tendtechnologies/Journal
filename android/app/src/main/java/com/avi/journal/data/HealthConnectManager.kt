package com.avi.journal.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/** Steps and sleep for one calendar day. Both independently optional. */
data class DayHealth(
    val date: LocalDate,
    val steps: Long? = null,
    val sleep: Duration? = null,
) {
    val sleepHours: Double? get() = sleep?.let { it.toMinutes() / 60.0 }
}

enum class HealthAvailability {
    /** Health Connect is installed and usable. */
    AVAILABLE,

    /** Present but too old — the provider needs updating. */
    UPDATE_REQUIRED,

    /** Not installed, or the device doesn't support it at all. */
    UNAVAILABLE,
}

/**
 * Reads steps and sleep from Health Connect.
 *
 * Nothing here is required for the app to work. Every method degrades to null
 * rather than throwing, because a journal whose writing screen breaks when a
 * fitness permission is missing would be a bad trade.
 *
 * Health data is deliberately NOT copied into Firestore. It already syncs to a
 * new phone through Health Connect, so mirroring it onto a server would
 * duplicate health measurements for no gain — and turn an entry store into a
 * medical data store, with the privacy obligations that carries. The same
 * reasoning WeightTracker's CloudSync gives for leaving weights out.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        // Without this, Health Connect quietly truncates any range longer than
        // 30 days instead of failing, so a year of correlations would be
        // computed from a month of data with no indication anything was missing.
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.UPDATE_REQUIRED
            else -> HealthAvailability.UNAVAILABLE
        }

    private fun client(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    fun createPermissionRequestContract() =
        PermissionController.createRequestPermissionResultContract()

    private suspend fun granted(): Set<String> {
        val controller = client()?.permissionController ?: return emptySet()
        return runCatching { controller.getGrantedPermissions() }.getOrDefault(emptySet())
    }

    suspend fun hasAnyPermission(): Boolean = granted().any {
        it == HealthPermission.getReadPermission(StepsRecord::class) ||
            it == HealthPermission.getReadPermission(SleepSessionRecord::class)
    }

    suspend fun missingPermissions(): Set<String> = permissions - granted()

    /**
     * Steps and sleep for each day in [from]..[to], keyed by date.
     *
     * Returns an empty map rather than an error when nothing is granted, so
     * callers never have to branch on permissions to render.
     */
    suspend fun readRange(from: LocalDate, to: LocalDate): Map<LocalDate, DayHealth> {
        val hc = client() ?: return emptyMap()
        val granted = granted()

        val start = from.atStartOfDay()
        val end = to.plusDays(1).atStartOfDay()

        val steps = if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
            runCatching { readSteps(hc, start, end) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val sleep = if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
            runCatching { readSleep(hc, start, end) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        return (steps.keys + sleep.keys).associateWith { date ->
            DayHealth(date = date, steps = steps[date], sleep = sleep[date])
        }
    }

    private suspend fun readSteps(
        hc: HealthConnectClient,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Map<LocalDate, Long> {
        val buckets: List<AggregationResultGroupedByPeriod> = hc.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        return buckets.mapNotNull { bucket ->
            val count = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            bucket.startTime.toLocalDate() to count
        }.toMap()
    }

    /**
     * Sleep, attributed to the day you WOKE UP, not the day you lay down.
     *
     * This is the whole reason sleep is read as sessions rather than aggregated
     * into daily buckets. A night that starts at 23:40 on Tuesday and ends at
     * 07:10 on Wednesday belongs, for anything this app does with it, to
     * Wednesday — it is the sleep behind Wednesday's mood. Bucketing by start
     * time would file it under Tuesday and quietly shift every sleep/mood
     * correlation by a day for anyone who goes to bed before midnight.
     *
     * Overlapping sessions (a nap recorded by two apps) are summed, which can
     * overcount. Deduplicating properly needs per-record provenance, and
     * over-reporting an hour is a smaller error than dropping a real session.
     */
    private suspend fun readSleep(
        hc: HealthConnectClient,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Map<LocalDate, Duration> {
        val zone = ZoneId.systemDefault()
        val totals = mutableMapOf<LocalDate, Duration>()
        var pageToken: String? = null

        do {
            val response = hc.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    // Widened by a day at the start so a session that began the
                    // night before the range still counts toward its wake day.
                    timeRangeFilter = TimeRangeFilter.between(start.minusDays(1), end),
                    pageToken = pageToken,
                )
            )

            response.records.forEach { session ->
                val wakeDay = session.endTime.atZone(zone).toLocalDate()
                if (wakeDay.isBefore(start.toLocalDate()) || !wakeDay.isBefore(end.toLocalDate())) {
                    return@forEach
                }
                val duration = Duration.between(session.startTime, session.endTime)
                if (!duration.isNegative && !duration.isZero) {
                    totals[wakeDay] = (totals[wakeDay] ?: Duration.ZERO).plus(duration)
                }
            }

            pageToken = response.pageToken
        } while (pageToken != null)

        return totals
    }
}
