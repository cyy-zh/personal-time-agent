package io.github.cyyzh.personaltimeagent.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import io.github.cyyzh.personaltimeagent.data.AgentDatabase
import io.github.cyyzh.personaltimeagent.data.UsageSampleEntity
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class AppInterval(val packageName: String, val startMs: Long, val endMs: Long, val source: String = "usage_events") {
    val durationMs get() = (endMs - startMs).coerceAtLeast(0)
}
data class UsageObservation(val intervals: List<AppInterval>, val permissionGranted: Boolean, val coverageStartMs: Long, val coverageEndMs: Long)

/** On-demand, interval-based observation; never treats an app as an internal activity category. */
object UsageObservationReader {
    fun read(c: Context, fromMs: Long, toMs: Long): UsageObservation {
        require(toMs >= fromMs && toMs - fromMs <= 31L * 86400000L)
        if (!PlatformGates.enabled(c, PlatformGates.USAGE) || !PlatformGates.usagePermission(c))
            return UsageObservation(emptyList(), false, fromMs, toMs)
        val usage = c.getSystemService(UsageStatsManager::class.java)
        // Do not carry foreground state across unobserved gaps or reboot boundaries.
        val events = usage.queryEvents(fromMs, toMs) ?: return UsageObservation(emptyList(), false, fromMs, toMs)
        val event = UsageEvents.Event()
        var foreground: String? = null
        var began = fromMs
        val result = mutableListOf<AppInterval>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val time = event.timeStamp
            val pkg = event.packageName
            if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE || event.eventType == UsageEvents.Event.KEYGUARD_SHOWN) {
                if (foreground != null && time > fromMs) result += AppInterval(foreground, began.coerceAtLeast(fromMs), time.coerceAtMost(toMs))
                foreground = null
                continue
            }
            if (pkg == null) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (foreground != null && time > fromMs) result += AppInterval(foreground, began.coerceAtLeast(fromMs), time.coerceAtMost(toMs))
                    foreground = pkg
                    began = time.coerceAtLeast(fromMs)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (foreground == pkg) {
                    if (time > fromMs) result += AppInterval(pkg, began.coerceAtLeast(fromMs), time.coerceAtMost(toMs))
                    foreground = null
                }
            }
        }
        if (foreground != null && toMs > began) result += AppInterval(foreground, began.coerceAtLeast(fromMs), toMs)
        if (!PlatformGates.enabled(c, PlatformGates.USAGE) || !PlatformGates.usagePermission(c)) return UsageObservation(emptyList(), false, fromMs, toMs)
        return UsageObservation(result.filter { it.durationMs > 0 }, true, fromMs, toMs)
    }
}

object UsageCollection {
    private const val WORK = "pta_usage_collection"
    fun start(c: Context) {
        if (!PlatformGates.enabled(c, PlatformGates.USAGE)) return
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UsageCollectionWorker>(15, TimeUnit.MINUTES).build())
    }
    fun stop(c: Context) = WorkManager.getInstance(c).cancelUniqueWork(WORK)
    fun collect(c: Context) {
        val epoch = PlatformGates.epoch(c, PlatformGates.USAGE)
        if (!PlatformGates.stillEnabled(c, PlatformGates.USAGE, epoch)) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (day in listOf(today.minusDays(1), today)) {
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
            val observation = UsageObservationReader.read(c, start, end)
            if (!observation.permissionGranted || !PlatformGates.stillEnabled(c, PlatformGates.USAGE, epoch)) return
            val totals = observation.intervals.groupBy { it.packageName }.mapValues { (_, spans) -> spans.sumOf { it.durationMs } }
            runBlocking {
                val dao = AgentDatabase.get(c).usageDao()
                for ((pkg, duration) in totals) {
                    if (!PlatformGates.stillEnabled(c, PlatformGates.USAGE, epoch)) return@runBlocking
                    dao.upsert(UsageSampleEntity("$start:$pkg", start, pkg, duration))
                }
            }
            if (PlatformGates.stillEnabled(c, PlatformGates.USAGE, epoch))
                c.getSharedPreferences("usage_coverage", 0).edit().putLong("$start-observed_until", end).commit()
        }
    }
}

class UsageCollectionWorker(c: Context, params: WorkerParameters) : Worker(c, params) {
    override fun doWork(): Result = try { UsageCollection.collect(applicationContext); Result.success() }
        catch (_: Exception) { Result.retry() }
}
