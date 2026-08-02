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

/**
 * An activity started while the server was out of reach.
 *
 * It exists only on this device until it is replayed, and carries everything the
 * eventual POST will need. [localId] is negative, so a queued entry can never be
 * mistaken for — or collide with — one the server issued.
 */
data class PendingStart(
    val localId: Int,
    val activityId: Int,
    val beginIso: String,
    /** Null while it is still running; set when it is stopped, also offline. */
    val endIso: String? = null,
    val tags: String? = null,
    val description: String? = null,
)

/**
 * A stop taken while the server was out of reach, for an entry the server
 * already knows about.
 *
 * [beginIso] is that entry's own start, kept because Kimai's PATCH insists on
 * being given one; [endIso] is when the user actually stopped it, which is why
 * this can't just be replayed as a plain stop later. [description] carries a
 * pomodoro's period breakdown, which is written as part of stopping one.
 */
data class PendingStop(
    val entryId: Int,
    val beginIso: String,
    val endIso: String,
    val description: String? = null,
)

/**
 * Everything taken on the device that the server hasn't been told about yet.
 *
 * Only the two signals that can't wait are ever queued — starting an activity
 * and stopping one. Everything else in the app still goes straight to the
 * server and still fails when it isn't there.
 */
data class PendingQueue(
    val starts: List<PendingStart> = emptyList(),
    val stops: List<PendingStop> = emptyList(),
    /** Counts down from -1, so ids stay unique across restarts. */
    val nextLocalId: Int = -1,
) {
    val size: Int get() = starts.size + stops.size
    val isEmpty: Boolean get() = size == 0

    /** The queued starts that haven't been stopped yet. */
    val running: List<PendingStart> get() = starts.filter { it.endIso == null }
}

/** A queued entry's id is negative; the server never issues those. */
fun isQueued(entryId: Int): Boolean = entryId < 0

/** The queued start as the timesheet record it will become once it lands. */
fun PendingStart.asEntry(projectId: Int): TimesheetEntry = TimesheetEntry(
    id = localId,
    begin = beginIso,
    end = endIso,
    duration = 0,
    description = description,
    tags = tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
    activity = activityId,
    project = projectId,
).withDuration()

/** The compact shape the cache and every date-range screen are built on. */
fun TimesheetActive.asEntry(projectId: Int): TimesheetEntry = TimesheetEntry(
    id = id,
    begin = begin.orEmpty(),
    end = end,
    duration = 0,
    description = description,
    tags = tags,
    activity = activity?.id ?: 0,
    project = project?.id ?: projectId,
).withDuration()

/** The expanded shape the timer screen and the notification want. */
fun TimesheetEntry.asActive(activities: List<Activity>): TimesheetActive = TimesheetActive(
    id = id,
    begin = begin,
    end = end,
    description = description,
    tags = tags,
    activity = activities.firstOrNull { it.id == activity }
        ?.let { NamedRef(it.id, it.name) } ?: NamedRef(activity),
    project = project?.let { NamedRef(it) },
)

/**
 * Fold the queue into a window of server entries, so every screen sees the day
 * as the user left it: a queued stop ends the entry it targets, and a queued
 * start is an entry the server hasn't heard of yet.
 *
 * [from] and [to] are epoch millis, matched against each entry's begin the same
 * way the API's own range query does — start inclusive, end exclusive.
 */
fun mergePending(
    entries: List<TimesheetEntry>,
    queue: PendingQueue,
    projectId: Int,
    from: Long,
    to: Long,
): List<TimesheetEntry> {
    if (queue.isEmpty) return entries
    val stops = queue.stops.associateBy { it.entryId }
    val patched = entries.map { e ->
        val stop = stops[e.id] ?: return@map e
        e.copy(end = stop.endIso, description = stop.description ?: e.description).withDuration()
    }
    val queued = queue.starts
        .map { it.asEntry(projectId) }
        .filter { (parseKimaiMillis(it.begin) ?: return@filter false) in from until to }
    return (patched + queued).sortedBy { parseKimaiMillis(it.begin) ?: 0L }
}

/**
 * The queue on disk: one JSON file in the app's private storage, held in memory
 * and rewritten atomically, the same way the local-only database is. It has to
 * survive the process being killed — the signals in it are the only record that
 * the user started or stopped anything.
 */
class PendingStore(context: Context) {
    private val file = File(context.filesDir, "pending-queue.json")
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(PendingQueue::class.java)

    private val mutex = Mutex()
    @Volatile private var cached: PendingQueue? = null

    suspend fun read(): PendingQueue = mutex.withLock { loadLocked() }

    /** Apply [change] to the queue and persist the result. */
    suspend fun <T> mutate(change: (PendingQueue) -> Pair<PendingQueue, T>): T = mutex.withLock {
        val (next, result) = change(loadLocked())
        writeLocked(next)
        result
    }

    /** Queue a start, handing back the record with the id it was given. */
    suspend fun addStart(
        activityId: Int,
        beginIso: String,
        tags: String?,
        description: String?,
    ): PendingStart = mutate { q ->
        val start = PendingStart(
            localId = q.nextLocalId,
            activityId = activityId,
            beginIso = beginIso,
            tags = tags,
            description = description,
        )
        q.copy(starts = q.starts + start, nextLocalId = q.nextLocalId - 1) to start
    }

    /** Stop a queued start — nothing needs sending, it simply ends where it ends. */
    suspend fun endStart(localId: Int, endIso: String, description: String? = null) = mutate { q ->
        q.copy(
            starts = q.starts.map {
                if (it.localId == localId) {
                    it.copy(endIso = endIso, description = description ?: it.description)
                } else it
            },
        ) to Unit
    }

    /** Queue a stop for an entry the server issued. */
    suspend fun addStop(stop: PendingStop) = mutate { q ->
        q.copy(stops = q.stops.filterNot { it.entryId == stop.entryId } + stop) to Unit
    }

    /** Move a queued start in time; the only edit the queue accepts. */
    suspend fun retime(localId: Int, beginIso: String, endIso: String?) = mutate { q ->
        q.copy(
            starts = q.starts.map {
                if (it.localId == localId) it.copy(beginIso = beginIso, endIso = endIso) else it
            },
        ) to Unit
    }

    /** Drop a queued start outright — it was never sent, so nothing is undone. */
    suspend fun removeStart(localId: Int) = mutate { q ->
        q.copy(starts = q.starts.filterNot { it.localId == localId }) to Unit
    }

    suspend fun removeStop(entryId: Int) = mutate { q ->
        q.copy(stops = q.stops.filterNot { it.entryId == entryId }) to Unit
    }

    private suspend fun loadLocked(): PendingQueue {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            try {
                if (file.exists()) adapter.fromJson(file.readText()) else null
            } catch (_: Exception) {
                null   // corrupt or half-written: an empty queue beats a crash
            }
        } ?: PendingQueue()
        cached = loaded
        return loaded
    }

    private suspend fun writeLocked(queue: PendingQueue) {
        cached = withContext(Dispatchers.IO) {
            val json = adapter.toJson(queue)
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.writeText(json)
                if (!tmp.renameTo(file)) file.writeText(json)
            } finally {
                tmp.delete()
            }
            queue
        }
    }
}
