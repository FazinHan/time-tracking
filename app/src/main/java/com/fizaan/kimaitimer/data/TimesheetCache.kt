package com.fizaan.kimaitimer.data

import android.content.Context
import com.fizaan.kimaitimer.util.parseKimaiMillis
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

    /** Forget a deleted entry, so an offline screen can't resurrect it. */
    suspend fun remove(entryId: Int) = mutex.withLock {
        val current = loadLocked()
        if (current.entries.none { it.id == entryId }) return@withLock
        writeLocked(current.copy(entries = current.entries.filterNot { it.id == entryId }))
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
