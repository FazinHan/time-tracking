package com.fizaan.timetracker.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The tags a fresh local store starts with — the app's productivity scheme. */
val LocalSeedTags = listOf("productive", "semi-productive", "unproductive", "life things")

/** The single customer and project everything local hangs off. */
const val LOCAL_ID = 1
const val LOCAL_NAME = "Local"

/**
 * Everything a serverless install owns. The shape mirrors what the server
 * returns, so the rest of the app cannot tell the difference.
 */
data class LocalData(
    val activities: List<Activity> = emptyList(),
    /** Every entry ever recorded, oldest first. A running one has no `end`. */
    val entries: List<TimesheetEntry> = emptyList(),
    val tags: List<String> = LocalSeedTags,
    val nextActivityId: Int = 1,
    val nextEntryId: Int = 1,
)

/**
 * The whole database of a serverless install: one JSON file in the app's
 * private storage, read once and held in memory, rewritten atomically on every
 * change.
 *
 * Unlike [TimesheetCache] — which is a disposable copy of what a server already
 * has, and is capped — this *is* the data. Nothing is ever evicted from it, and
 * nothing leaves the device.
 */
class LocalStore(context: Context) {
    private val file = File(context.filesDir, "local-data.json")
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(LocalData::class.java)

    private val mutex = Mutex()
    @Volatile private var cached: LocalData? = null

    suspend fun read(): LocalData = mutex.withLock { loadLocked() }

    /** Apply [change] to the stored data and persist the result. */
    suspend fun <T> mutate(change: (LocalData) -> Pair<LocalData, T>): T = mutex.withLock {
        val (next, result) = change(loadLocked())
        writeLocked(next)
        result
    }

    /** Bytes on disk and how many entries are in it, for the Tools screen. */
    suspend fun stats(): CacheStats {
        val data = read()
        return CacheStats(
            bytes = if (file.exists()) file.length() else 0L,
            entries = data.entries.size,
            lastSync = 0L,
        )
    }

    /**
     * Adopt what the offline cache is holding, but only into an empty store.
     *
     * Switching an existing install to local-only should leave the app looking
     * the way it does with the server unreachable rather than blank; from that
     * point the two never meet again, and nothing here is ever sent anywhere.
     */
    suspend fun seedFrom(snapshot: CacheSnapshot) = mutex.withLock {
        val current = loadLocked()
        if (current.entries.isNotEmpty() || current.activities.isNotEmpty()) return@withLock
        if (snapshot.entries.isEmpty() && snapshot.activities.isEmpty()) return@withLock
        val tags = (LocalSeedTags + snapshot.entries.flatMap { it.tags.orEmpty() })
            .filter { it.isNotBlank() }
            .distinct()
        writeLocked(
            LocalData(
                activities = snapshot.activities,
                entries = snapshot.entries,
                tags = tags,
                nextActivityId = (snapshot.activities.maxOfOrNull { it.id } ?: 0) + 1,
                nextEntryId = (snapshot.entries.maxOfOrNull { it.id } ?: 0) + 1,
            )
        )
    }

    private suspend fun loadLocked(): LocalData {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            try {
                if (file.exists()) adapter.fromJson(file.readText()) else null
            } catch (_: Exception) {
                null   // corrupt or half-written: better an empty store than a crash
            }
        } ?: LocalData()
        cached = loaded
        return loaded
    }

    private suspend fun writeLocked(data: LocalData) {
        cached = withContext(Dispatchers.IO) {
            val json = adapter.toJson(data)
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.writeText(json)
                if (!tmp.renameTo(file)) file.writeText(json)
            } finally {
                tmp.delete()
            }
            data
        }
    }
}
