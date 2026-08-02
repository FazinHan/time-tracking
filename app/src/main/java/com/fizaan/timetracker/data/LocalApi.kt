package com.fizaan.timetracker.data

import com.fizaan.timetracker.util.formatKimai
import com.fizaan.timetracker.util.parseKimaiMillis
import java.time.LocalDateTime

/** Kimai's own ceiling on concurrent timers, kept so the UI behaves the same. */
private const val MAX_ACTIVE = 2

/**
 * The server, replaced by a file.
 *
 * Serverless mode is implemented by handing the app a different [KimaiApi]: one
 * that answers out of [LocalStore] instead of over the network. Every screen,
 * the pomodoro and the batch tools go through this interface, so none of them
 * needed to learn about the difference — and nothing here can reach the
 * network, so a local install never so much as looks for a server.
 *
 * Semantics follow the real API where the app depends on them: PATCH is a
 * partial update, stopping stamps an end, deletes are permanent. Where Kimai is
 * merely restrictive it is not imitated — an unknown tag is kept rather than
 * silently dropped, and nothing is rounded to the whole minute.
 */
class LocalApi(private val store: LocalStore) : KimaiApi {

    override suspend fun version(): VersionInfo = VersionInfo(version = "local")

    override suspend fun customers(): List<Customer> =
        listOf(Customer(id = LOCAL_ID, name = LOCAL_NAME))

    override suspend fun projects(): List<Project> =
        listOf(Project(id = LOCAL_ID, name = LOCAL_NAME, customer = LOCAL_ID))

    override suspend fun activities(): List<Activity> = store.read().activities

    override suspend fun tags(): List<String> = store.read().tags

    override suspend fun active(): List<TimesheetActive> =
        store.read().let { data -> data.entries.filter { it.end == null }.map { data.expand(it) } }

    override suspend fun recent(size: Int): List<TimesheetActive> = store.read().let { data ->
        data.entries
            .sortedByDescending { parseKimaiMillis(it.begin) ?: 0L }
            .distinctBy { it.activity }
            .take(size)
            .map { data.expand(it) }
    }

    override suspend fun timesheets(begin: String, end: String, size: Int): List<TimesheetEntry> {
        val from = parseKimaiMillis(begin) ?: return emptyList()
        val to = parseKimaiMillis(end) ?: return emptyList()
        return store.read().entries
            .filter { (parseKimaiMillis(it.begin) ?: return@filter false) in from until to }
            .sortedBy { parseKimaiMillis(it.begin) ?: 0L }
            .take(size)
    }

    override suspend fun createTimesheet(body: TimesheetCreate): CreatedTimesheet =
        store.mutate { data ->
            check(data.entries.count { it.end == null } < MAX_ACTIVE) {
                "Only $MAX_ACTIVE timers can run at once."
            }
            val entry = TimesheetEntry(
                id = data.nextEntryId,
                begin = body.begin,
                end = null,
                duration = 0,
                description = body.description,
                tags = splitTags(body.tags),
                activity = body.activity,
                project = body.project,
            )
            data.copy(
                entries = data.entries + entry,
                tags = data.tags.plusNew(entry.tags.orEmpty()),
                nextEntryId = data.nextEntryId + 1,
            ) to CreatedTimesheet(entry.id)
        }

    override suspend fun stop(id: Int): CreatedTimesheet = store.mutate { data ->
        val entry = data.entries.firstOrNull { it.id == id }
            ?: error("No such entry: $id")
        val end = LocalDateTime.now()
        data.replacing(entry.stoppedAt(end)) to CreatedTimesheet(id)
    }

    /** Partial, like Kimai's: anything left null keeps the value it had. */
    override suspend fun updateTimesheet(id: Int, body: TimesheetUpdate): CreatedTimesheet =
        store.mutate { data ->
            val entry = data.entries.firstOrNull { it.id == id }
                ?: error("No such entry: $id")
            val tags = body.tags?.let { splitTags(it) } ?: entry.tags
            val updated = entry.copy(
                begin = body.begin,
                end = body.end ?: entry.end,
                description = body.description ?: entry.description,
                tags = tags,
                activity = body.activity ?: entry.activity,
            ).withDuration()
            data.replacing(updated)
                .copy(tags = data.tags.plusNew(tags.orEmpty())) to CreatedTimesheet(id)
        }

    override suspend fun deleteTimesheet(id: Int) = store.mutate { data ->
        data.copy(entries = data.entries.filterNot { it.id == id }) to Unit
    }

    override suspend fun createActivity(body: ActivityCreate): Activity = store.mutate { data ->
        val activity = Activity(
            id = data.nextActivityId,
            name = body.name,
            project = body.project,
            visible = body.visible,
        )
        data.copy(
            activities = data.activities + activity,
            nextActivityId = data.nextActivityId + 1,
        ) to activity
    }

    override suspend fun updateActivityColor(id: Int, body: ActivityColorUpdate): Activity =
        updateActivity(id) { it.copy(color = body.color) }

    override suspend fun updateActivityName(id: Int, body: ActivityNameUpdate): Activity =
        updateActivity(id) { it.copy(name = body.name) }

    /** No server palette to obey; the app falls back to its own swatches. */
    override suspend fun configColors(): Map<String, String> = emptyMap()

    private suspend fun updateActivity(id: Int, change: (Activity) -> Activity): Activity =
        store.mutate { data ->
            val activity = data.activities.firstOrNull { it.id == id }
                ?: error("No such activity: $id")
            val updated = change(activity)
            data.copy(
                activities = data.activities.map { if (it.id == id) updated else it },
            ) to updated
        }
}

// ---- helpers ----

private fun LocalData.expand(entry: TimesheetEntry) = TimesheetActive(
    id = entry.id,
    begin = entry.begin,
    end = entry.end,
    description = entry.description,
    tags = entry.tags,
    activity = activities.firstOrNull { it.id == entry.activity }
        ?.let { NamedRef(it.id, it.name) } ?: NamedRef(entry.activity),
    project = NamedRef(LOCAL_ID, LOCAL_NAME),
)

private fun LocalData.replacing(entry: TimesheetEntry) =
    copy(entries = entries.map { if (it.id == entry.id) entry else it })

private fun TimesheetEntry.stoppedAt(end: LocalDateTime) =
    copy(end = formatKimai(end)).withDuration()

/** Keep `duration` consistent with the timestamps; 0 while still running. */
internal fun TimesheetEntry.withDuration(): TimesheetEntry {
    val b = parseKimaiMillis(begin)
    val e = parseKimaiMillis(end)
    return copy(duration = if (b == null || e == null) 0 else ((e - b) / 1000).coerceAtLeast(0))
}

private fun splitTags(tags: String?): List<String> =
    tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Unknown tags join the list rather than being dropped, as they would be. */
private fun List<String>.plusNew(tags: List<String>): List<String> =
    (this + tags.filter { it.isNotBlank() }).distinct()
