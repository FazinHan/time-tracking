package com.fizaan.timetracker.data

import android.content.Context
import com.fizaan.timetracker.util.parseKimaiMillis
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Hard ceiling for the on-disk cache. Oldest entries are dropped past it. */
const val CACHE_MAX_BYTES: Long = 20L * 1024

/**
 * Everything kept on disk: every timesheet entry downloaded so far (oldest
 * first) plus the activity list needed to render them, and when the newest
 * server response landed.
 */
data class CacheSnapshot(
    val entries: List<TimesheetEntry> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val lastSync: Long = 0L,
) {
    /** Cached entries whose begin falls in [begin, end), oldest first. */
    fun between(begin: LocalDateTime, end: LocalDateTime): List<TimesheetEntry> {
        val from = epochMillis(begin)
        val to = epochMillis(end)
        return entries.filter {
            val t = parseKimaiMillis(it.begin) ?: return@filter false
            t in from until to
        }
    }
}

/** What the Tools screen reports about local storage use. */
data class CacheStats(val bytes: Long, val entries: Int, val lastSync: Long)

/**
 * On-device store of timesheet data so the calendar, visualisations and tools
 * keep working when the server can't be reached. Every successful range fetch
 * is folded in; the store is capped at [CACHE_MAX_BYTES] by evicting the oldest
 * entries, so the most recent history is what survives.
 */
class TimesheetCache(context: Context) {
    private val file = File(context.filesDir, "timesheet-cache.json")
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(CacheSnapshot::class.java)

    private val mutex = Mutex()
    @Volatile private var cached: CacheSnapshot? = null

    suspend fun snapshot(): CacheSnapshot = mutex.withLock { loadLocked() }

    suspend fun stats(): CacheStats {
        val snap = snapshot()
        return CacheStats(
            bytes = if (file.exists()) file.length() else 0L,
            entries = snap.entries.size,
            lastSync = snap.lastSync,
        )
    }

    /**
     * Fold a freshly fetched window into the cache. Cached entries beginning
     * inside [begin, end) are replaced wholesale so server-side deletions
     * propagate; anything outside the window is left untouched. A non-empty
     * [activities] list replaces the stored one.
     */
    suspend fun save(
        begin: LocalDateTime,
        end: LocalDateTime,
        entries: List<TimesheetEntry>,
        activities: List<Activity>,
    ) = mutex.withLock {
        val current = loadLocked()
        val from = epochMillis(begin)
        val to = epochMillis(end)
        val outside = current.entries.filter {
            val t = parseKimaiMillis(it.begin) ?: return@filter true
            t < from || t >= to
        }
        // Fresh records first, so an edited entry that moved out of the window
        // still wins over the stale copy kept under the same id.
        val merged = (entries + outside)
            .distinctBy { it.id }
            .sortedBy { parseKimaiMillis(it.begin) ?: 0L }
        writeLocked(
            CacheSnapshot(
                entries = merged,
                activities = activities.ifEmpty { current.activities },
                lastSync = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Fold a handful of entries in without clearing a window around them.
     *
     * The running timers are read on their own, outside any date range, and
     * they are exactly what the app must not forget if the server disappears a
     * moment later — so they are upserted rather than treated as a complete
     * answer for some span of time.
     */
    suspend fun remember(
        entries: List<TimesheetEntry>,
        activities: List<Activity>,
    ) = mutex.withLock {
        val current = loadLocked()
        if (entries.isEmpty() && activities.isEmpty()) return@withLock
        val merged = (entries + current.entries)
            .distinctBy { it.id }
            .sortedBy { parseKimaiMillis(it.begin) ?: 0L }
        writeLocked(
            CacheSnapshot(
                entries = merged,
                activities = activities.ifEmpty { current.activities },
                lastSync = System.currentTimeMillis(),
            )
        )
    }

    /** Forget deleted entries, so an offline screen can't resurrect them. */
    suspend fun forget(ids: Set<Int>) = mutex.withLock {
        val current = loadLocked()
        if (current.entries.none { it.id in ids }) return@withLock
        writeLocked(current.copy(entries = current.entries.filterNot { it.id in ids }))
    }

    suspend fun remove(entryId: Int) = forget(setOf(entryId))

    /**
     * Write an end onto a cached entry that was still running.
     *
     * The running entry is remembered without one — that is what makes it the
     * running entry — so unless the end is recorded at the moment the timer is
     * stopped, the saved copy goes on claiming to be running for as long as it
     * survives, and an offline timer screen believes it.
     */
    suspend fun close(entryId: Int, endIso: String) = mutex.withLock {
        val current = loadLocked()
        val target = current.entries.firstOrNull { it.id == entryId && it.end == null }
            ?: return@withLock
        writeLocked(
            current.copy(
                entries = current.entries.map {
                    if (it.id == target.id) it.copy(end = endIso).withDuration() else it
                },
            )
        )
    }

    /**
     * Cached entries that claim to be running but aren't among [runningIds] —
     * the complete set the server just reported. Each one was stopped somewhere
     * this app didn't see it happen, so the saved copy is wrong about the only
     * thing it is ever consulted for.
     */
    suspend fun staleRunning(runningIds: Set<Int>): List<TimesheetEntry> = mutex.withLock {
        loadLocked().entries.filter { it.end == null && it.id !in runningIds }
    }

    private suspend fun loadLocked(): CacheSnapshot {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            try {
                if (file.exists()) adapter.fromJson(file.readText()) else null
            } catch (_: Exception) {
                null   // corrupt or half-written file: start over rather than crash
            }
        } ?: CacheSnapshot()
        cached = loaded
        return loaded
    }

    /** Serialise, evict oldest entries until under budget, then write atomically. */
    private suspend fun writeLocked(snapshot: CacheSnapshot) {
        cached = withContext(Dispatchers.IO) {
            var candidate = snapshot
            var json = adapter.toJson(candidate)
            var bytes = json.toByteArray().size.toLong()
            var guard = 0
            while (bytes > CACHE_MAX_BYTES && candidate.entries.size > 1 && guard++ < 5) {
                val perEntry = (bytes / candidate.entries.size).coerceAtLeast(1L)
                val keep = (CACHE_MAX_BYTES * 95 / 100 / perEntry)
                    .toInt().coerceIn(1, candidate.entries.size - 1)
                // Entries are sorted oldest→newest, so keeping the tail drops
                // the oldest history first.
                candidate = candidate.copy(entries = candidate.entries.takeLast(keep))
                json = adapter.toJson(candidate)
                bytes = json.toByteArray().size.toLong()
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.writeText(json)
                if (!tmp.renameTo(file)) file.writeText(json)
            } finally {
                tmp.delete()
            }
            candidate
        }
    }
}

private fun epochMillis(dt: LocalDateTime): Long =
    dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
