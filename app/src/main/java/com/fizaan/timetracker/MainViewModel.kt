package com.fizaan.timetracker

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fizaan.timetracker.data.Activity
import com.fizaan.timetracker.data.ActivityColorUpdate
import com.fizaan.timetracker.data.ActivityCreate
import com.fizaan.timetracker.data.ActivityNameUpdate
import com.fizaan.timetracker.data.ApiProvider
import com.fizaan.timetracker.data.CacheSnapshot
import com.fizaan.timetracker.data.LOCAL_ID
import com.fizaan.timetracker.data.LOCAL_NAME
import com.fizaan.timetracker.data.LocalStore
import com.fizaan.timetracker.data.Customer
import com.fizaan.timetracker.data.NO_ENTRY
import com.fizaan.timetracker.data.PendingQueue
import com.fizaan.timetracker.data.PendingStop
import com.fizaan.timetracker.data.PendingStore
import com.fizaan.timetracker.data.Prefs
import com.fizaan.timetracker.data.Project
import com.fizaan.timetracker.data.TagCreate
import com.fizaan.timetracker.data.TimesheetActive
import com.fizaan.timetracker.data.TimesheetCache
import com.fizaan.timetracker.data.TimesheetCreate
import com.fizaan.timetracker.data.TimesheetEntry
import com.fizaan.timetracker.data.TimesheetUpdate
import com.fizaan.timetracker.data.asActive
import com.fizaan.timetracker.data.asEntry
import com.fizaan.timetracker.data.isQueued
import com.fizaan.timetracker.data.mergePending
import com.fizaan.timetracker.chart.ChartFormat
import com.fizaan.timetracker.chart.ChartImage
import com.fizaan.timetracker.chart.MonthChart
import com.fizaan.timetracker.chart.TrendChart
import com.fizaan.timetracker.chart.TrendMetric
import com.fizaan.timetracker.chart.leadInFrom
import com.fizaan.timetracker.chart.monthChart
import com.fizaan.timetracker.chart.rollingTrend
import com.fizaan.timetracker.chart.trendChart
import com.fizaan.timetracker.export.ExportFormat
import com.fizaan.timetracker.export.ExportRow
import com.fizaan.timetracker.export.ExportStore
import com.fizaan.timetracker.export.ImportEntry
import com.fizaan.timetracker.export.ImportRead
import com.fizaan.timetracker.export.csvBytes
import com.fizaan.timetracker.export.exportRows
import com.fizaan.timetracker.export.planImport
import com.fizaan.timetracker.export.readEntries
import com.fizaan.timetracker.export.readTable
import com.fizaan.timetracker.export.writePdf
import com.fizaan.timetracker.export.xlsxBytes
import androidx.core.app.NotificationManagerCompat
import com.fizaan.timetracker.pomodoro.ALERT_NOTIFICATION_ID
import com.fizaan.timetracker.pomodoro.Phase
import com.fizaan.timetracker.pomodoro.PomodoroAlarm
import com.fizaan.timetracker.pomodoro.PomodoroSettings
import com.fizaan.timetracker.pomodoro.phaseAt
import com.fizaan.timetracker.pomodoro.sessionSummary
import androidx.compose.ui.graphics.toArgb
import com.fizaan.timetracker.ui.DefaultAccent
import com.fizaan.timetracker.util.entryLocalDate
import com.fizaan.timetracker.util.entrySeconds
import com.fizaan.timetracker.util.epochMillis
import com.fizaan.timetracker.util.formatDuration
import com.fizaan.timetracker.util.formatKimai
import com.fizaan.timetracker.util.parseKimaiLocal
import com.fizaan.timetracker.util.parseKimaiMillis
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class AppScreen { TIMER, POMODORO, VIZ, SHEET, CALENDAR, TOOLS }
enum class VizTab { PIE, BAR }
enum class PieMode { ACTIVITY, TAG }
enum class VizPeriod { DAY, WEEK, MONTH, YEAR }
enum class SheetPeriod { ALL, DAY, WEEK, MONTH, YEAR, CUSTOM }

/** Sentinel tag filter meaning "entries with no tags". */
const val UNTAGGED = ""

/** The tag that qualifies an activity for a pomodoro. */
const val PRODUCTIVE_TAG = "productive"

/** How many activities may be tracked at once — Kimai's own limit, mirrored. */
const val MAX_TIMERS = 2

/**
 * Marks a screen as rendering locally saved data instead of a live server
 * response. [savedAt] is when that data was last downloaded, [reason] why the
 * server couldn't be reached.
 */
data class CacheInfo(val savedAt: Long, val reason: String)

/**
 * Visualisation screen state. Entries are refetched on every open/change.
 * [pieOffset] counts whole periods back from the present one — 0 is today /
 * this week / this month / this year, -1 the one before it, and so on.
 */
data class VizState(
    val loading: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    val tab: VizTab = VizTab.PIE,
    val pieMode: PieMode = PieMode.ACTIVITY,
    val period: VizPeriod = VizPeriod.DAY,
    val pieOffset: Int = 0,
    val pieEntries: List<TimesheetEntry> = emptyList(),
    val barEntries: List<TimesheetEntry> = emptyList(),   // last 30 days
    val activities: List<Activity> = emptyList(),
)

/**
 * The calendar days a pie covers, both ends inclusive. [offset] steps whole
 * periods into the past; the current period is never cut short at today, so a
 * past month reads as the whole month.
 */
fun pieRange(period: VizPeriod, offset: Int, today: LocalDate): Pair<LocalDate, LocalDate> {
    val n = offset.toLong()
    return when (period) {
        VizPeriod.DAY -> today.plusDays(n).let { it to it }
        VizPeriod.WEEK -> today.with(DayOfWeek.MONDAY).plusWeeks(n)
            .let { it to it.plusDays(6) }
        VizPeriod.MONTH -> today.withDayOfMonth(1).plusMonths(n)
            .let { it to it.plusMonths(1).minusDays(1) }
        VizPeriod.YEAR -> today.withDayOfYear(1).plusYears(n)
            .let { it to it.plusYears(1).minusDays(1) }
    }
}

/** Timesheet screen state. */
data class SheetState(
    val loading: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    /** The server couldn't be reached, so only the queue can be edited. */
    val offline: Boolean = false,
    val saving: Boolean = false,
    val entries: List<TimesheetEntry> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val allTags: List<String> = emptyList(),
    val colorChoices: Map<String, String> = emptyMap(),   // server palette, name → hex
    val editing: TimesheetEntry? = null,
    val pendingDelete: TimesheetEntry? = null,            // awaiting the confirmation dialog
    val deleting: Boolean = false,
    // Filters. A null activity/tag/range means "no filter"; tag == UNTAGGED
    // matches entries without tags. from/to are inclusive calendar days.
    val filterActivityId: Int? = null,
    /** Several activities at once, as the pie's "Other" wedge opens them. */
    val filterActivityIds: List<Int>? = null,
    val filterTag: String? = null,
    val filterFrom: LocalDate? = null,
    val filterTo: LocalDate? = null,
    val filterPreset: SheetPeriod = SheetPeriod.ALL,
)

/**
 * Calendar (week-view) state. [anchor] is the newest (right-most) day shown;
 * the number of columns is decided by the screen orientation in the UI. Entries
 * cover a padded window around the anchor so paging back a few days is instant.
 */
data class CalendarState(
    val loading: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    val anchor: LocalDate = LocalDate.now(),
    val entries: List<TimesheetEntry> = emptyList(),
    val activities: List<Activity> = emptyList(),
)

/**
 * Result of the frequency tool. Rates are sessions ÷ the *whole* selected span
 * — every day in the range counts, whether anything was tracked on it or not —
 * so a fortnight off pulls the average down the way it should. [activeDays] is
 * kept for the summary line, as context rather than a divisor.
 *
 * [firstEntry] is the earliest session found in the range: everything before it
 * is a period we have no evidence about, which is what the short-history
 * warning is drawn from.
 */
data class FreqResult(
    val activityId: Int,
    val from: LocalDate,
    val to: LocalDate,
    val sessions: Int,
    val totalSeconds: Long,
    val spanDays: Int,
    val activeDays: Int,
    val firstEntry: LocalDate?,
) {
    /** Days of actual observation: from the first session to the end of the range. */
    val observedDays: Int
        get() = firstEntry?.let { (ChronoUnit.DAYS.between(it, to) + 1).toInt() } ?: 0
}

/**
 * What a batch operation will do. [RENAME_ACTIVITY] and [MOVE_ACTIVITY] are the
 * two outcomes of typing an activity name: an unused name renames the activity
 * the entries belong to, an existing one can only absorb them.
 */
enum class BatchAction { RENAME_ACTIVITY, MOVE_ACTIVITY, SET_TAGS, SET_COLOR, DELETE }

/**
 * Batch edit tool. The filters narrow the timesheet down to a set of entries —
 * any subset of them may be left unset — and an action is then applied to every
 * entry that matched.
 */
data class BatchState(
    val filterActivityId: Int? = null,
    val filterTag: String? = null,
    val minMinutes: String = "",          // inclusive lower bound, blank = none
    val maxMinutes: String = "",          // inclusive upper bound, blank = none
    val from: LocalDate = LocalDate.now().minusDays(29),
    val to: LocalDate = LocalDate.now(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val matches: List<TimesheetEntry> = emptyList(),
    // Pending action awaiting confirmation, plus what it was told to apply.
    val pending: BatchAction? = null,
    val pendingActivityId: Int? = null,
    val pendingActivityName: String = "",
    val pendingTags: List<String> = emptyList(),
    val pendingColor: String? = null,
    val confirmedOnce: Boolean = false,   // delete needs a second confirmation
    val applying: Boolean = false,
    val progress: String? = null,
    val done: String? = null,
)

/**
 * Export tool. Kimai's own export lives behind a browser login the API token
 * can't reach, so the files are written here from the same data every other
 * screen is drawn from — which also means the tool is only offered where that
 * data comes from a server at all.
 */
data class ExportState(
    val from: LocalDate = LocalDate.now().withDayOfMonth(1),
    val to: LocalDate = LocalDate.now(),
    val format: ExportFormat = ExportFormat.CSV,
    val running: Boolean = false,
    /** Where the finished file went, for the confirmation line. */
    val saved: String? = null,
    /** A rendered document waiting to be handed to the system print dialog. */
    val printFile: String? = null,
)

/**
 * What an import did, once it has been done.
 *
 * Every number is worth reporting: [added] is the point of it, [duplicates] is
 * the reassurance that importing the same file twice changed nothing, and
 * [ignored] covers rows the file couldn't be read out of.
 */
data class ImportResult(
    val fileName: String,
    val added: Int,
    val duplicates: Int,
    val activitiesCreated: Int,
    /** Tags the file used that the server had never seen. */
    val tagsCreated: Int = 0,
    val ignored: Int,
    /** Set when the server refused some of the entries, with the first reason. */
    val refused: Int = 0,
    val refusedWhy: String? = null,
)

/** Import tool state — one file at a time, read and sent on the spot. */
data class ImportState(
    val running: Boolean = false,
    val fileName: String? = null,
    val result: ImportResult? = null,
)

/**
 * Rolling-average plotter. [chart] is null until the first plot: the tool is a
 * question until it has been asked, and the colour override underneath it has
 * nothing to recolour before then.
 *
 * [colorOverride] is a hex the user picked for this plot only — the activity's
 * own colour, which every other screen draws from, is left alone.
 */
data class TrendState(
    val activityId: Int? = null,
    val metric: TrendMetric = TrendMetric.TIME,
    val window: Int = 7,
    val from: LocalDate = LocalDate.now().minusDays(89),
    val to: LocalDate = LocalDate.now(),
    val chart: TrendChart? = null,
    val colorOverride: String? = null,
    val running: Boolean = false,
    val exporting: Boolean = false,
    /** Where the last exported picture went, for the confirmation line. */
    val saved: String? = null,
)

/** Frequency calendar: one activity, one month, redrawn as either changes. */
data class HeatState(
    val activityId: Int? = null,
    val month: YearMonth = YearMonth.now(),
    val chart: MonthChart? = null,
    val colorOverride: String? = null,
    val running: Boolean = false,
    val exporting: Boolean = false,
    val saved: String? = null,
)

/** Tools screen state. Hosts the frequency calculator, batch edit and export. */
data class ToolsState(
    val loading: Boolean = false,
    /** Local-only install: the stored data is the database, and it has no cap. */
    val serverless: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    val computing: Boolean = false,
    val cacheBytes: Long = 0L,
    val cacheEntries: Int = 0,
    /** Queued signals the server hasn't been told about yet. */
    val queued: Int = 0,
    val activities: List<Activity> = emptyList(),
    val freqActivityId: Int? = null,
    val freqFrom: LocalDate = LocalDate.now().minusDays(29),
    val freqTo: LocalDate = LocalDate.now(),
    val freqResult: FreqResult? = null,
    val allTags: List<String> = emptyList(),
    val colorChoices: Map<String, String> = emptyMap(),
    val batch: BatchState = BatchState(),
    val export: ExportState = ExportState(),
    val import: ImportState = ImportState(),
    val trend: TrendState = TrendState(),
    val heat: HeatState = HeatState(),
)

/**
 * Pomodoro state. A session is one unbroken Kimai entry — [entryId], begun at
 * [startMs] — and the work/break phase is derived from that instant, never
 * counted, so nothing drifts while the app is closed.
 */
data class PomodoroState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val settings: PomodoroSettings = PomodoroSettings(),
    /** The lengths the running session began with; edits wait for the next one. */
    val sessionSettings: PomodoroSettings = PomodoroSettings(),
    val activities: List<Activity> = emptyList(),   // only the productive-tagged ones
    val startMs: Long = 0L,
    /** How far skips have pushed the schedule ahead of the clock. */
    val skew: Long = 0L,
    val entryId: Int? = null,
    val activityName: String = "",
    val showPicker: Boolean = false,
    val showSettings: Boolean = false,
    val alert: Phase? = null,                       // a boundary just passed
) {
    val running: Boolean get() = startMs > 0L && entryId != null

    /** The instant the schedule runs from; the phase is read at this. */
    val timelineOrigin: Long get() = startMs - skew
}

/** Whole-app UI state. */
data class UiState(
    val configured: Boolean = false,
    /** Packed ARGB the whole theme is built from; chosen in setup. */
    val accent: Int = DefaultAccent.toArgb(),
    val screen: AppScreen = AppScreen.TIMER,
    val loading: Boolean = false,
    val busy: Boolean = false,          // an action (start/stop/create) is in flight
    val error: String? = null,
    /** Last contact with the server failed: starts and stops go to the queue. */
    val offline: Boolean = false,
    /** How many queued signals are waiting to be sent. */
    val queued: Int = 0,
    // Up to two timers can run at once: [running] is the one started first and
    // owns the big clock, [second] is the later one.
    val running: TimesheetActive? = null,
    val second: TimesheetActive? = null,
    val showStopChoice: Boolean = false,
    val activities: List<Activity> = emptyList(),
    val recent: List<TimesheetActive> = emptyList(),
    val allTags: List<String> = emptyList(),
    val projectName: String = "",
    val showPickDialog: Boolean = false,
    val showCreateDialog: Boolean = false,
    // Tag prompt (first-time tagging / re-tagging an activity).
    val showTagDialog: Boolean = false,
    val tagActivityId: Int? = null,
    val tagActivityName: String = "",
    val tagSelected: List<String> = emptyList(),
    val tagStartAfter: Boolean = false,
)

/** Setup-flow state (first run / reconfigure). */
data class SetupState(
    val step: Int = 0,                  // 0 = credentials, 1 = pick customer+project
    /** Reached from the running app rather than a first run, so it can be left. */
    val canLeave: Boolean = false,
    /** Local-only: no server is contacted, and the URL/token are irrelevant. */
    val serverless: Boolean = false,
    /** Whether a server was ever configured — decides which warning is shown. */
    val hadServer: Boolean = false,
    val baseUrl: String = "",
    val token: String = "",
    val useLegacy: Boolean = false,
    val legacyUser: String = "",
    val testing: Boolean = false,
    val error: String? = null,
    val customers: List<Customer> = emptyList(),
    val projects: List<Project> = emptyList(),
    val selectedCustomerId: Int? = null,
    val selectedProjectId: Int? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val prefs = Prefs(app)
    private val cache = TimesheetCache(app)
    /** The same instance [ApiProvider] serves local-only mode from — see there. */
    private val localStore get() = ApiProvider.localStore(ctx)
    private val pending = PendingStore(app)
    /** The queue, mirrored in memory so a screen can be drawn from it directly. */
    private var queue = PendingQueue()
    private val notifier = RunningNotifier(app)
    private val beginFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _setup = MutableStateFlow(
        SetupState(
            baseUrl = defaultUrlHint(),
            serverless = prefs.serverless,
            hadServer = prefs.baseUrl.isNotBlank(),
        )
    )
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _viz = MutableStateFlow(VizState())
    val viz: StateFlow<VizState> = _viz.asStateFlow()

    private val _sheet = MutableStateFlow(SheetState())
    val sheet: StateFlow<SheetState> = _sheet.asStateFlow()

    private val _calendar = MutableStateFlow(CalendarState())
    val calendar: StateFlow<CalendarState> = _calendar.asStateFlow()

    private val _tools = MutableStateFlow(ToolsState())
    val tools: StateFlow<ToolsState> = _tools.asStateFlow()

    private val _pomodoro = MutableStateFlow(
        PomodoroState(
            settings = prefs.pomodoroSettings,
            sessionSettings = prefs.pomodoroSessionSettings,
            startMs = prefs.pomodoroStartMs,
            skew = prefs.pomodoroSkew,
            entryId = prefs.pomodoroEntryId.takeIf { it != NO_ENTRY },
            activityName = prefs.pomodoroActivityName,
        )
    )
    val pomodoro: StateFlow<PomodoroState> = _pomodoro.asStateFlow()

    init {
        _ui.value = _ui.value.copy(accent = prefs.accentColor)
        viewModelScope.launch { readQueue() }
        if (prefs.isConfigured) {
            _ui.value = _ui.value.copy(configured = true, projectName = prefs.projectName)
            refresh()
        }
    }

    /**
     * Repaint the app. Takes effect on the next frame; nothing is reloaded, and
     * the launcher icon deliberately isn't touched here — see
     * [syncLauncherIcon].
     */
    fun setAccent(argb: Int) {
        prefs.accentColor = argb
        _ui.value = _ui.value.copy(accent = argb)
    }

    /**
     * Apply a colour and leave the settings screen.
     *
     * Reconfiguring is the only way to reach the theme picker, so pressing the
     * button has to put the app back where it was — not drop the user into the
     * connection flow they never asked for.
     */
    fun useAccent(argb: Int) {
        setAccent(argb)
        leaveSetup()
    }

    /**
     * Back out of settings, changing nothing. Only ever possible when the app is
     * already set up: on a first run there is nowhere to go back to.
     */
    fun leaveSetup() {
        if (!prefs.isConfigured) return
        _ui.value = _ui.value.copy(configured = true, projectName = prefs.projectName)
    }

    /**
     * Point the launcher at the icon nearest the accent.
     *
     * Switching an `activity-alias` tears down the task it was launched from, so
     * doing this the moment a colour is picked looks exactly like the app
     * crashing. It is done once the app is off screen instead, where the same
     * work is invisible.
     */
    fun syncLauncherIcon() = LauncherIcon.apply(ctx, prefs.accentColor)

    private fun defaultUrlHint(): String =
        if (prefs.baseUrl.isNotBlank()) prefs.baseUrl else "http://192.168.0.110:8000"

    private fun api() = ApiProvider.get(ctx, prefs)

    // ---------------- Offline queue ----------------
    //
    // With a server configured but out of reach, two things still have to work:
    // starting an activity and stopping one. Both are written to a queue on the
    // device and replayed the next time the server answers. Nothing else is ever
    // queued — every other action still goes straight to the server and still
    // fails when it isn't there.

    /** Re-read the queue into memory and publish how much of it is waiting. */
    private suspend fun readQueue() {
        queue = pending.read()
        _ui.value = _ui.value.copy(queued = queue.size)
    }

    /**
     * Whether [e] means "no server", as opposed to the server saying no. Only
     * the former may be queued: a refusal is an answer, and repeating it later
     * would just be refused again.
     */
    private fun unreachable(e: Exception): Boolean =
        e is IOException || (e.message ?: "").let {
            it.contains("Failed to connect", true) ||
                it.contains("Unable to resolve", true) ||
                it.contains("timeout", true)
        }

    /** Local-only mode has no server to be cut off from, so it never queues. */
    private fun canQueue(e: Exception): Boolean = !prefs.serverless && unreachable(e)

    /**
     * Send everything the queue is holding, oldest first, and drop each signal
     * as it lands. Stops go first: freeing a running entry is what makes room
     * for a queued start under Kimai's limit on concurrent timers.
     *
     * A still-absent server leaves the queue exactly as it was; anything else
     * the server says is surfaced, because a signal it refuses will never leave
     * on its own.
     */
    private suspend fun syncQueue(): Boolean {
        if (prefs.serverless) return true
        // Read it here rather than trusting the mirror: on a cold start this
        // runs alongside the queue's first load off disk, and would otherwise
        // find it empty and leave a real backlog sitting there.
        readQueue()
        if (queue.isEmpty) return true
        var reachable = true
        try {
            queue.stops.forEach { stop ->
                api().updateTimesheet(
                    stop.entryId,
                    TimesheetUpdate(
                        begin = stop.beginIso, end = stop.endIso,
                        description = stop.description,
                    ),
                )
                // Once the stop leaves the queue it stops standing in for the
                // end of that entry, so the saved copy has to carry it instead.
                cache.close(stop.entryId, stop.endIso)
                pending.removeStop(stop.entryId)
            }
            queue.starts.sortedBy { it.beginIso }.forEach { start ->
                val created = api().createTimesheet(
                    TimesheetCreate(
                        begin = start.beginIso,
                        project = prefs.projectId,
                        activity = start.activityId,
                        description = start.description,
                        tags = start.tags,
                    )
                )
                // Created open, then closed: the POST takes no end of its own.
                start.endIso?.let { end ->
                    api().updateTimesheet(
                        created.id,
                        TimesheetUpdate(
                            begin = start.beginIso, end = end,
                            description = start.description, tags = start.tags,
                        ),
                    )
                }
                pending.removeStart(start.localId)
            }
        } catch (e: Exception) {
            reachable = !unreachable(e)
            if (reachable) {
                _ui.value = _ui.value.copy(
                    error = "Couldn't send a queued change: ${friendly(e)}",
                )
            }
        }
        readQueue()
        return reachable
    }

    /** A window of server entries with the queue folded in, ready to render. */
    private fun merged(
        entries: List<TimesheetEntry>,
        begin: LocalDateTime,
        end: LocalDateTime,
    ): List<TimesheetEntry> =
        if (prefs.serverless) entries
        else mergePending(
            entries, queue, prefs.projectId, epochMillis(begin), epochMillis(end),
        )

    // ---------------- Setup flow ----------------

    fun onUrl(v: String) { _setup.value = _setup.value.copy(baseUrl = v) }
    fun onToken(v: String) { _setup.value = _setup.value.copy(token = v) }
    fun onLegacyUser(v: String) { _setup.value = _setup.value.copy(legacyUser = v) }
    fun onUseLegacy(v: Boolean) { _setup.value = _setup.value.copy(useLegacy = v) }

    /** Flip between local-only and server-backed. Nothing is committed until saved. */
    fun onServerless(v: Boolean) {
        _setup.value = _setup.value.copy(serverless = v, error = null, step = 0)
    }

    /**
     * Commit local-only mode: one synthetic customer and project to hang
     * everything off, and — for an install that had a server — whatever the
     * offline cache still holds, so the app looks the way it does with the
     * server unreachable rather than empty. Nothing local is ever uploaded.
     */
    fun finishLocalSetup() {
        _setup.value = _setup.value.copy(testing = true, error = null)
        viewModelScope.launch {
            localStore.seedFrom(cache.snapshot())
            prefs.serverless = true
            prefs.projectId = LOCAL_ID
            prefs.projectName = LOCAL_NAME
            prefs.customerName = LOCAL_NAME
            ApiProvider.invalidate()
            _setup.value = _setup.value.copy(testing = false)
            _ui.value = _ui.value.copy(configured = true, projectName = prefs.projectName)
            refresh()
        }
    }
    fun onSelectCustomer(id: Int) {
        val projects = _setup.value.projects.filter { it.customer == null || it.customer == id }
        _setup.value = _setup.value.copy(
            selectedCustomerId = id,
            selectedProjectId = projects.firstOrNull()?.id,
        )
    }
    fun onSelectProject(id: Int) { _setup.value = _setup.value.copy(selectedProjectId = id) }

    fun testConnection() {
        val s = _setup.value
        // Persist credentials so the shared client/interceptor use them.
        prefs.baseUrl = s.baseUrl.trim()
        prefs.token = s.token.trim()
        prefs.authMode = if (s.useLegacy) "legacy" else "bearer"
        prefs.legacyUser = s.legacyUser.trim()
        ApiProvider.invalidate()

        _setup.value = s.copy(testing = true, error = null)
        viewModelScope.launch {
            try {
                api().version()
                val customers = api().customers()
                val projects = api().projects()
                val curCustomer = customers.firstOrNull()?.id
                val curProject = projects.firstOrNull { it.customer == null || it.customer == curCustomer }?.id
                    ?: projects.firstOrNull()?.id
                _setup.value = _setup.value.copy(
                    testing = false,
                    step = 1,
                    customers = customers,
                    projects = projects,
                    selectedCustomerId = curCustomer,
                    selectedProjectId = curProject,
                    error = if (projects.isEmpty()) "Connected, but no projects found." else null,
                )
            } catch (e: Exception) {
                _setup.value = _setup.value.copy(testing = false, error = friendly(e))
            }
        }
    }

    fun finishSetup() {
        val s = _setup.value
        prefs.serverless = false
        ApiProvider.invalidate()
        val projectId = s.selectedProjectId ?: return
        val project = s.projects.firstOrNull { it.id == projectId }
        val customer = s.customers.firstOrNull { it.id == s.selectedCustomerId }
        prefs.projectId = projectId
        prefs.projectName = project?.name ?: ""
        prefs.customerName = customer?.name ?: (project?.parentTitle ?: "")
        _ui.value = _ui.value.copy(configured = true, projectName = prefs.projectName)
        refresh()
    }

    /** Re-open setup from the main screen. */
    fun reconfigure() {
        _setup.value = SetupState(
            baseUrl = prefs.baseUrl.ifBlank { defaultUrlHint() },
            token = prefs.token,
            useLegacy = prefs.authMode == "legacy",
            legacyUser = prefs.legacyUser,
            serverless = prefs.serverless,
            hadServer = prefs.baseUrl.isNotBlank(),
            canLeave = prefs.isConfigured,
        )
        _ui.value = _ui.value.copy(configured = false)
    }

    // ---------------- Main flow ----------------

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            // A queue that just failed to reach the server has already waited
            // out the connection; asking again only makes the user wait twice
            // for the same answer.
            if (!syncQueue()) {
                offlineRefresh()
                return@launch
            }
            try {
                // Oldest first, so the timer that started earlier keeps the big clock.
                val server = api().active().sortedBy { parseKimaiMillis(it.begin) ?: 0L }
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                // "recent" is a nice-to-have; don't let it break the main screen.
                val recent = try { api().recent(8) } catch (e: Exception) { _ui.value.recent }
                // Tags are used to build the picker; best-effort like recent.
                val tags = try { api().tags() } catch (e: Exception) { _ui.value.allTags }
                // Keep what's running on the device too: it's read outside any
                // date range, so nothing else would ever write it down, and it
                // is the first thing needed if the server goes away.
                cache.remember(server.map { it.asEntry(prefs.projectId) }, acts)
                // This is also the only moment the app learns that something it
                // saved as running has since stopped.
                settleCachedRunning(server.mapTo(mutableSetOf()) { it.id }, acts)
                // Anything the queue couldn't hand over still runs on the device.
                val actives = server + queuedActives(acts)
                _ui.value = _ui.value.copy(
                    loading = false,
                    offline = false,
                    running = actives.getOrNull(0),
                    second = actives.getOrNull(1),
                    activities = acts,
                    recent = recent, allTags = tags,
                )
                syncNotification()
            } catch (e: Exception) {
                if (canQueue(e)) offlineRefresh() else {
                    _ui.value = _ui.value.copy(loading = false, error = friendly(e))
                }
            }
        }
    }

    /**
     * Bring saved entries that claim to be running back into line with the
     * server, which has just named every entry that really is.
     *
     * Stopping a timer in this app records its end as it happens, so what this
     * catches is a timer stopped somewhere else — Kimai's own web dashboard, or
     * another device. The truthful fix is to re-read the window those entries
     * fall in, because only the server knows when they actually ended; if that
     * read fails, they are dropped instead, since a saved entry that lies about
     * still running is worse than a gap the next fetch fills.
     */
    private suspend fun settleCachedRunning(runningIds: Set<Int>, acts: List<Activity>) {
        val stale = cache.staleRunning(runningIds)
        if (stale.isEmpty()) return
        // Re-reading months of history to settle one forgotten entry isn't worth
        // it; past a month, the saved copy is simply dropped.
        val cutoff = LocalDate.now().minusDays(31).atStartOfDay()
        val (recent, ancient) = stale.partition {
            (parseKimaiLocal(it.begin) ?: cutoff.minusDays(1)) >= cutoff
        }
        if (ancient.isNotEmpty()) cache.forget(ancient.mapTo(mutableSetOf()) { it.id })
        if (recent.isEmpty()) return
        val from = recent.minOf { parseKimaiLocal(it.begin) ?: cutoff }
            .toLocalDate().atStartOfDay()
        val to = LocalDateTime.now().plusMinutes(1)
        try {
            cache.save(from, to, api().timesheets(formatKimai(from), formatKimai(to)), acts)
        } catch (e: Exception) {
            cache.forget(recent.mapTo(mutableSetOf()) { it.id })
        }
    }

    /** The queued starts still running, as the timer screen wants to see them. */
    private fun queuedActives(activities: List<Activity>): List<TimesheetActive> =
        queue.running
            .sortedBy { it.beginIso }
            .map { it.asEntry(prefs.projectId).asActive(activities) }

    /**
     * The timer screen with no server behind it.
     *
     * What was running when the server was last heard from is still running now
     * — unless it was stopped into the queue since — and whatever was started
     * offline runs alongside it. The activity list is the one already on the
     * device, which is what a queued start has to be chosen from.
     */
    private suspend fun offlineRefresh() {
        val snap = cache.snapshot()
        val stopped = queue.stops.map { it.entryId }.toSet()
        val acts = snap.activities.filter { it.visible }.sortedBy { it.name.lowercase() }
        val stillRunning = snap.entries
            .filter { it.end == null && it.id !in stopped }
            .sortedBy { parseKimaiMillis(it.begin) ?: 0L }
            .map { it.asActive(acts) }
        val actives = stillRunning + queuedActives(acts)
        val recent = snap.entries
            .sortedByDescending { parseKimaiMillis(it.begin) ?: 0L }
            .distinctBy { it.activity }
            .take(8)
            .map { it.asActive(acts) }
        _ui.value = _ui.value.copy(
            loading = false,
            offline = true,
            error = null,
            running = actives.getOrNull(0),
            second = actives.getOrNull(1),
            activities = acts.ifEmpty { _ui.value.activities },
            recent = recent.ifEmpty { _ui.value.recent },
            allTags = _ui.value.allTags.ifEmpty {
                snap.entries.flatMap { it.tags.orEmpty() }.filter { it.isNotBlank() }.distinct()
            },
        )
        syncNotification()
    }

    fun openPicker() { _ui.value = _ui.value.copy(showPickDialog = true, error = null) }
    fun dismissPicker() { _ui.value = _ui.value.copy(showPickDialog = false) }
    fun openCreate() { _ui.value = _ui.value.copy(showCreateDialog = true) }
    fun dismissCreate() { _ui.value = _ui.value.copy(showCreateDialog = false) }

    fun startActivity(activityId: Int) {
        // If we already know how this activity should be tagged (either the user
        // set it before, or we can seed it from how it was tagged on the server),
        // start straight away. Otherwise prompt once and remember the choice.
        val known = resolveTag(activityId)
        if (known != null) {
            start(activityId, description = null, tags = known.ifBlank { null })
        } else {
            openTagDialog(activityId, startAfter = true)
        }
    }

    /**
     * The tag string to attach to a start of [activityId], or null if undecided.
     * Prefers the user's stored choice; falls back to seeding from the most
     * recent server timesheet for that activity (and persists that seed).
     */
    private fun resolveTag(activityId: Int): String? {
        prefs.tagFor(activityId)?.let { return it }
        val seeded = recentTag(activityId) ?: return null
        prefs.setTag(activityId, seeded)
        return seeded
    }

    /** Comma-joined tags from the latest recent timesheet of [activityId], if any tagged. */
    private fun recentTag(activityId: Int): String? =
        _ui.value.recent
            .firstOrNull { it.activity?.id == activityId && !it.tags.isNullOrEmpty() }
            ?.tags?.joinToString(",")

    private fun openTagDialog(activityId: Int, startAfter: Boolean) {
        val name = _ui.value.activities.firstOrNull { it.id == activityId }?.name
            ?: _ui.value.recent.firstOrNull { it.activity?.id == activityId }?.activity?.name
            ?: ""
        val current = (prefs.tagFor(activityId) ?: recentTag(activityId))
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        _ui.value = _ui.value.copy(
            showPickDialog = false,
            showTagDialog = true,
            tagActivityId = activityId,
            tagActivityName = name,
            tagSelected = current,
            tagStartAfter = startAfter,
        )
    }

    /** Open the tag editor for an activity without starting it (long-press). */
    fun editTag(activityId: Int) = openTagDialog(activityId, startAfter = false)

    fun dismissTagDialog() {
        _ui.value = _ui.value.copy(showTagDialog = false, tagActivityId = null)
    }

    /** Save the chosen tags for the current dialog's activity; start it if requested. */
    fun confirmTag(tags: List<String>) {
        val activityId = _ui.value.tagActivityId ?: return
        val startAfter = _ui.value.tagStartAfter
        val joined = tags.joinToString(",")
        prefs.setTag(activityId, joined)
        _ui.value = _ui.value.copy(showTagDialog = false, tagActivityId = null)
        if (startAfter) start(activityId, description = null, tags = joined.ifBlank { null })
    }

    private fun start(activityId: Int, description: String?, tags: String?) {
        _ui.value = _ui.value.copy(busy = true, showPickDialog = false, error = null)
        viewModelScope.launch {
            try {
                openEntry(
                    activityId = activityId,
                    beginIso = LocalDateTime.now().format(beginFormat),
                    tags = tags?.ifBlank { null },
                    description = description?.ifBlank { null },
                )
                _ui.value = _ui.value.copy(busy = false)
                refresh()
            } catch (e: Exception) {
                // A rejected *second* start is almost always Kimai's own limit on
                // concurrent entries, which the error body doesn't reach us.
                val message = if (_ui.value.running != null && e.message?.contains("400") == true) {
                    "Kimai refused a second timer. Raise its active-entry hard " +
                        "limit to 2 to track two activities at once."
                } else {
                    friendly(e)
                }
                _ui.value = _ui.value.copy(busy = false, error = message)
            }
        }
    }

    /**
     * Open a timesheet entry and hand back its id — from the server when it
     * answers, from the queue when it doesn't. A queued id is negative.
     *
     * Once the server is known to be gone, the attempt is skipped outright
     * rather than making the user sit through another connection timeout for a
     * start that is going to be queued anyway. The ceiling of two concurrent
     * timers is kept on this side too, so what the queue eventually hands over
     * is something the server would have accepted at the time.
     */
    private suspend fun openEntry(
        activityId: Int,
        beginIso: String,
        tags: String?,
        description: String?,
    ): Int {
        if (!_ui.value.offline || prefs.serverless) {
            try {
                return api().createTimesheet(
                    TimesheetCreate(
                        begin = beginIso,
                        project = prefs.projectId,
                        activity = activityId,
                        description = description,
                        tags = tags,
                    )
                ).id
            } catch (e: Exception) {
                if (!canQueue(e)) throw e
            }
        }
        check(listOfNotNull(_ui.value.running, _ui.value.second).size < MAX_TIMERS) {
            "Two timers are already running. Stop one first."
        }
        return pending.addStart(activityId, beginIso, tags, description).localId
            .also { readQueue() }
    }

    /** Mirror the running timers onto the lock screen (or clear it when idle). */
    private fun syncNotification() = notifier.sync(_ui.value.running, _ui.value.second)

    /**
     * The stop button. With one timer running it stops it outright; with two,
     * it can't guess which one you meant, so it asks first.
     */
    fun stop() {
        val s = _ui.value
        if (s.running == null) return
        if (s.second != null) {
            _ui.value = s.copy(showStopChoice = true, error = null)
            return
        }
        stopEntry(s.running.id)
    }

    fun dismissStopChoice() { _ui.value = _ui.value.copy(showStopChoice = false) }

    /** Stop one specific running entry, whichever of the two it is. */
    fun stopEntry(id: Int) {
        _ui.value = _ui.value.copy(busy = true, error = null, showStopChoice = false)
        viewModelScope.launch {
            val begin = listOfNotNull(_ui.value.running, _ui.value.second)
                .firstOrNull { it.id == id }?.begin
            // A queued start has nothing to stop on the server; it just ends.
            if (isQueued(id)) {
                pending.endStart(id, formatKimai(LocalDateTime.now()))
                settleStop(id)
                return@launch
            }
            if (_ui.value.offline && !prefs.serverless) {
                if (queueStop(id, begin)) settleStop(id)
                return@launch
            }
            try {
                api().stop(id)
                settleStop(id)
            } catch (e: Exception) {
                if (canQueue(e)) {
                    if (queueStop(id, begin)) settleStop(id)
                    return@launch
                }
                _ui.value = _ui.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    /**
     * Stop a server entry on the device alone. The stop is remembered with the
     * instant it happened, not replayed later as "stop it now", so the entry
     * lands with the length it really had.
     *
     * False when it couldn't be queued: Kimai's PATCH insists on being given a
     * begin, and that can only come from the entry itself.
     */
    private suspend fun queueStop(id: Int, beginIso: String?, description: String? = null): Boolean {
        if (beginIso == null) {
            _ui.value = _ui.value.copy(
                busy = false,
                error = "Can't stop this offline — reconnect and try again.",
            )
            return false
        }
        pending.addStop(
            PendingStop(
                entryId = id,
                beginIso = formatKimai(parseKimaiLocal(beginIso) ?: LocalDateTime.now()),
                endIso = formatKimai(LocalDateTime.now()),
                description = description,
            )
        )
        return true
    }

    /** Take the stopped timer off the screen, then reload behind it. */
    private suspend fun settleStop(id: Int) {
        // The saved copy was written while it was running, and is what an
        // offline timer screen is drawn from — if the end isn't recorded here,
        // the next time the server is out of reach the app puts the timer back
        // on the screen and counts it up again.
        cache.close(id, formatKimai(LocalDateTime.now()))
        readQueue()
        val s = _ui.value
        // Promote the survivor so the UI settles before the refresh lands.
        val remaining = listOfNotNull(s.running, s.second).filter { it.id != id }
        _ui.value = s.copy(
            busy = false,
            running = remaining.getOrNull(0),
            second = remaining.getOrNull(1),
        )
        syncNotification()
        refresh()
    }

    fun createActivity(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                api().createActivity(ActivityCreate(name = trimmed, project = prefs.projectId))
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                _ui.value = _ui.value.copy(busy = false, showCreateDialog = false, activities = acts)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    // ---------------- Navigation ----------------

    fun navigate(screen: AppScreen) {
        _ui.value = _ui.value.copy(screen = screen)
        when (screen) {
            AppScreen.TIMER -> refresh()
            AppScreen.POMODORO -> loadPomodoro()
            AppScreen.VIZ -> loadViz()
            AppScreen.SHEET -> loadSheet()
            AppScreen.CALENDAR -> loadCalendar()
            AppScreen.TOOLS -> loadTools()
        }
    }

    // ---------------- Pomodoro ----------------

    /**
     * The activities a pomodoro may be spent on: those tagged "productive".
     * Tags live on timesheet entries rather than on activities, so an activity
     * counts as productive if it is remembered that way on this device or if
     * its recent entries on the server were tagged so.
     */
    private fun productiveActivities(
        all: List<Activity>,
        history: List<TimesheetEntry>,
    ): List<Activity> {
        val fromServer = history
            .filter { e -> e.tags.orEmpty().any { it.equals(PRODUCTIVE_TAG, ignoreCase = true) } }
            .map { it.activity }
            .toSet()
        return all.filter { act ->
            act.id in fromServer || prefs.tagFor(act.id).orEmpty()
                .split(",").any { it.trim().equals(PRODUCTIVE_TAG, ignoreCase = true) }
        }
    }

    /** Refresh the picker's activity list and re-sync a session left running. */
    fun loadPomodoro() {
        _pomodoro.value = _pomodoro.value.copy(
            loading = true, error = null,
            settings = prefs.pomodoroSettings,
            sessionSettings = prefs.pomodoroSessionSettings,
            startMs = prefs.pomodoroStartMs,
            skew = prefs.pomodoroSkew,
            entryId = prefs.pomodoroEntryId.takeIf { it != NO_ENTRY },
            activityName = prefs.pomodoroActivityName,
        )
        // An alarm may have been missed while the app was dead; re-arm the next.
        // With no session there is nothing to arm and nothing left to announce.
        if (prefs.pomodoroStartMs > 0L) PomodoroAlarm.scheduleNext(ctx)
        else PomodoroAlarm.cancel(ctx)
        viewModelScope.launch {
            syncQueue()
            try {
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                val today = LocalDate.now()
                val history = api().timesheets(
                    begin = formatKimai(today.minusDays(90).atStartOfDay()),
                    end = formatKimai(today.plusDays(1).atStartOfDay()),
                )
                // The session may have been stopped elsewhere (the timer screen,
                // Kimai itself); don't keep showing a clock for a dead entry. A
                // queued session is only the queue's to answer for.
                val id = _pomodoro.value.entryId
                val alive = when {
                    id == null -> true
                    isQueued(id) -> queue.running.any { it.localId == id }
                    else -> api().active().any { it.id == id }
                }
                if (!alive) clearPomodoroSession()
                _pomodoro.value = _pomodoro.value.copy(
                    loading = false,
                    activities = productiveActivities(acts, history),
                )
            } catch (e: Exception) {
                if (!canQueue(e)) {
                    _pomodoro.value = _pomodoro.value.copy(loading = false, error = friendly(e))
                    return@launch
                }
                // Offline: a pomodoro can still be started, so the picker is
                // filled from what the device already knows.
                val snap = cache.snapshot()
                _pomodoro.value = _pomodoro.value.copy(
                    loading = false, error = null,
                    activities = productiveActivities(
                        snap.activities.filter { it.visible }.sortedBy { it.name.lowercase() },
                        snap.entries,
                    ).ifEmpty { _pomodoro.value.activities },
                )
            }
        }
    }

    fun openPomodoroPicker() { _pomodoro.value = _pomodoro.value.copy(showPicker = true, error = null) }
    fun dismissPomodoroPicker() { _pomodoro.value = _pomodoro.value.copy(showPicker = false) }
    fun openPomodoroSettings() { _pomodoro.value = _pomodoro.value.copy(showSettings = true) }
    fun dismissPomodoroSettings() { _pomodoro.value = _pomodoro.value.copy(showSettings = false) }
    fun clearPomodoroError() { _pomodoro.value = _pomodoro.value.copy(error = null) }

    /**
     * Saved lengths take effect on the next session: a running one keeps the
     * shape it started with, since re-slicing it would rewrite periods already
     * taken.
     */
    fun savePomodoroSettings(settings: PomodoroSettings) {
        prefs.pomodoroSettings = settings
        _pomodoro.value = _pomodoro.value.copy(
            settings = prefs.pomodoroSettings, showSettings = false,
        )
    }

    /** Announce a boundary the app itself noticed while it was on screen. */
    fun onPomodoroPhaseStarted(kind: Phase) {
        NotificationManagerCompat.from(ctx).cancel(ALERT_NOTIFICATION_ID)
        _pomodoro.value = _pomodoro.value.copy(alert = kind)
    }

    fun dismissPomodoroAlert() {
        NotificationManagerCompat.from(ctx).cancel(ALERT_NOTIFICATION_ID)
        _pomodoro.value = _pomodoro.value.copy(alert = null)
    }

    /**
     * End the current period now and start the next one.
     *
     * The session's real start is untouched — that is what Kimai has — and the
     * *schedule* is moved forward by the unused remainder instead, so the phase
     * stays a pure function of an instant and a killed process still picks the
     * session up exactly where it left it. What was skipped is kept so the
     * description can report the periods at their true lengths.
     */
    fun skipPomodoroPhase() {
        val s = _pomodoro.value
        if (!s.running) return
        val now = System.currentTimeMillis()
        val slot = phaseAt(s.timelineOrigin, now, s.sessionSettings)
        val remaining = (slot.endMs - now).coerceAtLeast(0L)
        prefs.pomodoroSkips = prefs.pomodoroSkips + (slot.index to remaining)
        prefs.pomodoroSkew = prefs.pomodoroSkew + remaining
        _pomodoro.value = s.copy(skew = prefs.pomodoroSkew, alert = null)
        // The next boundary just moved; nothing announces a skip, the user did it.
        PomodoroAlarm.scheduleNext(ctx)
        NotificationManagerCompat.from(ctx).cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * Begin a session: one Kimai entry that stays open across every work and
     * break period, plus the alarm for the first boundary.
     */
    fun startPomodoro(activityId: Int) {
        val name = _pomodoro.value.activities.firstOrNull { it.id == activityId }?.name ?: ""
        _pomodoro.value = _pomodoro.value.copy(busy = true, showPicker = false, error = null)
        viewModelScope.launch {
            try {
                val begin = LocalDateTime.now()
                val beginIso = begin.format(beginFormat)
                val entryId = openEntry(
                    activityId = activityId,
                    beginIso = beginIso,
                    tags = (prefs.tagFor(activityId)?.ifBlank { null } ?: PRODUCTIVE_TAG),
                    description = null,
                )
                val startMs = begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                prefs.pomodoroStartMs = startMs
                prefs.pomodoroEntryId = entryId
                prefs.pomodoroActivityId = activityId
                prefs.pomodoroActivityName = name
                prefs.pomodoroBeginIso = beginIso
                prefs.pomodoroSessionSettings = _pomodoro.value.settings
                prefs.pomodoroSkew = 0L
                prefs.pomodoroSkips = emptyMap()
                _pomodoro.value = _pomodoro.value.copy(
                    busy = false, startMs = startMs, skew = 0L, entryId = entryId,
                    sessionSettings = prefs.pomodoroSessionSettings,
                    activityName = name, alert = null,
                )
                PomodoroAlarm.scheduleNext(ctx)
                refresh()
            } catch (e: Exception) {
                val message = if (_ui.value.running != null && e.message?.contains("400") == true) {
                    "Kimai refused another timer while one is already running."
                } else {
                    friendly(e)
                }
                _pomodoro.value = _pomodoro.value.copy(busy = false, error = message)
            }
        }
    }

    /**
     * End the session. The breakdown of work and break periods exists nowhere
     * else, so it is written into the entry's description before it is stopped.
     */
    fun stopPomodoro() {
        val s = _pomodoro.value
        val entryId = s.entryId ?: return
        _pomodoro.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            val summary = sessionSummary(
                s.startMs, System.currentTimeMillis(), s.sessionSettings, prefs.pomodoroSkips,
            )
            val beginIso = prefs.pomodoroBeginIso.ifBlank { formatKimai(LocalDateTime.now()) }
            // A queued session never reached the server; it simply ends, and
            // carries its breakdown along when the queue is finally sent.
            if (isQueued(entryId)) {
                pending.endStart(entryId, formatKimai(LocalDateTime.now()), summary)
                finishPomodoro(entryId)
                return@launch
            }
            if (_ui.value.offline && !prefs.serverless) {
                if (queueStop(entryId, beginIso, summary)) finishPomodoro(entryId)
                return@launch
            }
            try {
                api().updateTimesheet(
                    entryId,
                    TimesheetUpdate(begin = beginIso, description = summary),
                )
                api().stop(entryId)
                finishPomodoro(entryId)
            } catch (e: Exception) {
                if (canQueue(e)) {
                    if (queueStop(entryId, beginIso, summary)) finishPomodoro(entryId)
                    return@launch
                }
                _pomodoro.value = _pomodoro.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    private suspend fun finishPomodoro(entryId: Int) {
        // Same reason as [settleStop]: the saved copy still says it's running.
        cache.close(entryId, formatKimai(LocalDateTime.now()))
        clearPomodoroSession()
        _pomodoro.value = _pomodoro.value.copy(busy = false)
        readQueue()
        refresh()
    }

    private fun clearPomodoroSession() {
        PomodoroAlarm.cancel(ctx)
        prefs.clearPomodoroSession()
        _pomodoro.value = _pomodoro.value.copy(
            startMs = 0L, skew = 0L, entryId = null, activityName = "", alert = null,
        )
    }

    // ---------------- Calendar ----------------

    /** Fetch a padded window of entries around the calendar's anchor day. */
    fun loadCalendar() {
        _calendar.value = _calendar.value.copy(loading = true, error = null)
        viewModelScope.launch {
            syncQueue()
            val anchor = _calendar.value.anchor
            val from = anchor.minusDays(7).atStartOfDay()
            val to = anchor.plusDays(1).atStartOfDay()
            try {
                val acts = api().activities()
                val entries = api().timesheets(
                    begin = formatKimai(from), end = formatKimai(to),
                )
                cache.save(from, to, entries, acts)
                _calendar.value = _calendar.value.copy(
                    loading = false, entries = merged(entries, from, to),
                    activities = acts, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _calendar.value = if (saved == null) {
                    _calendar.value.copy(loading = false, error = friendly(e))
                } else {
                    _calendar.value.copy(
                        loading = false,
                        entries = merged(saved.first.between(from, to), from, to),
                        activities = saved.first.activities,
                        cached = saved.second,
                    )
                }
            }
        }
    }

    /** Move the visible window by [days] (negative = into the past) and reload. */
    fun shiftCalendar(days: Int) {
        _calendar.value = _calendar.value.copy(anchor = _calendar.value.anchor.plusDays(days.toLong()))
        loadCalendar()
    }

    fun calendarToday() {
        _calendar.value = _calendar.value.copy(anchor = LocalDate.now())
        loadCalendar()
    }

    fun clearCalendarError() { _calendar.value = _calendar.value.copy(error = null) }

    // ---------------- Tools ----------------

    fun loadTools() {
        _tools.value = _tools.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                // Both are only needed by the batch tool; don't fail the screen for them.
                val tags = try { api().tags() } catch (e: Exception) { _tools.value.allTags }
                val colors = try { api().configColors() } catch (e: Exception) { _tools.value.colorChoices }
                _tools.value = _tools.value.copy(
                    loading = false, activities = acts, allTags = tags,
                    colorChoices = colors, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _tools.value = if (saved == null) {
                    _tools.value.copy(loading = false, error = friendly(e))
                } else {
                    _tools.value.copy(
                        loading = false,
                        activities = saved.first.activities
                            .filter { it.visible }.sortedBy { it.name.lowercase() },
                        cached = saved.second,
                    )
                }
            }
            // Local-only: report the real database, not the disposable copy.
            val stats = if (prefs.serverless) localStore.stats() else cache.stats()
            _tools.value = _tools.value.copy(
                cacheBytes = stats.bytes, cacheEntries = stats.entries,
                serverless = prefs.serverless, queued = queue.size,
            )
        }
    }

    fun setFreqActivity(id: Int?) {
        _tools.value = _tools.value.copy(freqActivityId = id, freqResult = null)
    }

    fun setFreqFrom(date: LocalDate) {
        _tools.value = _tools.value.copy(freqFrom = date, freqResult = null)
    }

    fun setFreqTo(date: LocalDate) {
        _tools.value = _tools.value.copy(freqTo = date, freqResult = null)
    }

    /** Sum sessions and time for the chosen activity over the chosen date range. */
    fun computeFrequency() {
        val s = _tools.value
        val activityId = s.freqActivityId ?: return
        val from = s.freqFrom
        val to = s.freqTo
        if (to.isBefore(from)) {
            _tools.value = s.copy(error = "End date is before the start date.")
            return
        }
        _tools.value = s.copy(computing = true, error = null)
        viewModelScope.launch {
            syncQueue()
            val begin = from.atStartOfDay()
            val end = to.plusDays(1).atStartOfDay()
            try {
                val entries = api().timesheets(
                    begin = formatKimai(begin), end = formatKimai(end),
                )
                cache.save(begin, end, entries, emptyList())
                _tools.value = _tools.value.copy(
                    computing = false, cached = null,
                    freqResult = summarise(
                        activityId, from, to, merged(entries, begin, end),
                    ),
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _tools.value = if (saved == null) {
                    _tools.value.copy(computing = false, error = friendly(e))
                } else {
                    _tools.value.copy(
                        computing = false, cached = saved.second,
                        freqResult = summarise(
                            activityId, from, to,
                            merged(saved.first.between(begin, end), begin, end),
                        ),
                    )
                }
            }
            // Local-only: report the real database, not the disposable copy.
            val stats = if (prefs.serverless) localStore.stats() else cache.stats()
            _tools.value = _tools.value.copy(
                cacheBytes = stats.bytes, cacheEntries = stats.entries,
                serverless = prefs.serverless,
            )
        }
    }

    /** Roll a window of entries up into the frequency tool's result. */
    private fun summarise(
        activityId: Int,
        from: LocalDate,
        to: LocalDate,
        entries: List<TimesheetEntry>,
    ): FreqResult {
        val mine = entries.filter { it.activity == activityId }
        val now = System.currentTimeMillis()
        val total = mine.sumOf { entrySeconds(it.begin, it.end, it.duration, now) }
        val dates = mine.mapNotNull { entryLocalDate(it.begin) }
        return FreqResult(
            activityId = activityId,
            from = from,
            to = to,
            sessions = mine.size,
            totalSeconds = total,
            spanDays = (ChronoUnit.DAYS.between(from, to) + 1).coerceAtLeast(1).toInt(),
            activeDays = dates.distinct().size,
            firstEntry = dates.minOrNull(),
        )
    }

    fun clearToolsError() { _tools.value = _tools.value.copy(error = null) }

    // ---------------- Export ----------------

    private fun updateExport(block: (ExportState) -> ExportState) {
        _tools.value = _tools.value.copy(export = block(_tools.value.export))
    }

    /** Any change to what would be exported drops the last result's message. */
    fun setExportFrom(date: LocalDate) = updateExport { it.copy(from = date, saved = null) }
    fun setExportTo(date: LocalDate) = updateExport { it.copy(to = date, saved = null) }
    fun setExportFormat(format: ExportFormat) =
        updateExport { it.copy(format = format, saved = null) }

    /** The print dialog has been handed the document; stop offering it again. */
    fun exportPrintHandled() = updateExport { it.copy(printFile = null) }

    /**
     * Write the chosen range out in the chosen format.
     *
     * The window is fetched the way every other tool fetches one — server
     * first, the saved copy if it can't be reached — so an export made without
     * a server holds exactly what the app could still show, queued entries
     * included, rather than failing outright.
     */
    fun runExport() {
        val s = _tools.value.export
        if (s.to.isBefore(s.from)) {
            _tools.value = _tools.value.copy(error = "End date is before the start date.")
            return
        }
        updateExport { it.copy(running = true, saved = null, printFile = null) }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            syncQueue()
            val begin = s.from.atStartOfDay()
            val end = s.to.plusDays(1).atStartOfDay()
            try {
                val entries = toolsWindow(begin, end)
                val rows = exportRows(
                    entries = entries,
                    activities = _tools.value.activities.ifEmpty { cache.snapshot().activities },
                    customer = prefs.customerName,
                    project = prefs.projectName,
                )
                writeExport(s, rows)
            } catch (e: Exception) {
                updateExport { it.copy(running = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    /** Render [rows] and either save the file or stage it for the print dialog. */
    private suspend fun writeExport(s: ExportState, rows: List<ExportRow>) {
        val stamp = "${s.from}_${s.to}"
        val name = "timesheet-$stamp.${s.format.extension}"
        val title = prefs.projectName.ifBlank { "Timesheet" }
        val subtitle = "${s.from} to ${s.to} · ${rows.size} " +
            if (rows.size == 1) "entry" else "entries"
        val total = "Total: " + formatDuration(rows.sumOf { (it.hours * 3600).toLong() })

        val body: (java.io.OutputStream) -> Unit = when (s.format) {
            ExportFormat.CSV -> ({ out -> out.write(csvBytes(rows)) })
            ExportFormat.EXCEL -> ({ out -> out.write(xlsxBytes(rows)) })
            ExportFormat.PDF, ExportFormat.PRINT ->
                ({ out -> writePdf(rows, title, subtitle, total, out) })
        }

        if (s.format == ExportFormat.PRINT) {
            val file = ExportStore.scratch(ctx, name, body)
            updateExport { it.copy(running = false, printFile = file.absolutePath) }
        } else {
            val where = ExportStore.save(ctx, name, s.format.mime, body)
            updateExport { it.copy(running = false, saved = where) }
        }
    }

    // ---------------- Charts ----------------

    /**
     * The entries in a window: from the server when it answers, from the saved
     * copy when it doesn't. Either way the pending queue is folded in, so a
     * chart shows what the app knows rather than what the server has been told.
     */
    private suspend fun toolsWindow(begin: LocalDateTime, end: LocalDateTime): List<TimesheetEntry> =
        try {
            val fresh = api().timesheets(begin = formatKimai(begin), end = formatKimai(end))
            cache.save(begin, end, fresh, emptyList())
            _tools.value = _tools.value.copy(cached = null)
            merged(fresh, begin, end)
        } catch (e: Exception) {
            val saved = fallback(e) ?: throw e
            _tools.value = _tools.value.copy(cached = saved.second)
            merged(saved.first.between(begin, end), begin, end)
        }

    private fun updateTrend(block: (TrendState) -> TrendState) {
        _tools.value = _tools.value.copy(trend = block(_tools.value.trend))
    }

    /** Anything that changes what would be plotted takes the old plot away. */
    private fun resetTrend(block: (TrendState) -> TrendState) =
        updateTrend { block(it).copy(chart = null, saved = null) }

    fun setTrendActivity(id: Int?) = resetTrend { it.copy(activityId = id, colorOverride = null) }
    fun setTrendMetric(metric: TrendMetric) = resetTrend { it.copy(metric = metric) }
    fun setTrendWindow(days: Int) = resetTrend { it.copy(window = days.coerceIn(1, 365)) }
    fun setTrendFrom(date: LocalDate) = resetTrend { it.copy(from = date) }
    fun setTrendTo(date: LocalDate) = resetTrend { it.copy(to = date) }

    /** Recolours this plot only; the activity keeps the colour it had. */
    fun setTrendColor(hex: String?) = updateTrend { it.copy(colorOverride = hex, saved = null) }

    /**
     * Plot the rolling average.
     *
     * The window is fetched from [leadInFrom] rather than the first day asked
     * for, because a trailing average needs the days before the range to be a
     * real average — without them the line would start low and climb, and the
     * climb would be an artefact of the arithmetic rather than anything that
     * happened.
     */
    fun runTrend() {
        val s = _tools.value.trend
        val activityId = s.activityId ?: return
        if (s.to.isBefore(s.from)) {
            _tools.value = _tools.value.copy(error = "End date is before the start date.")
            return
        }
        updateTrend { it.copy(running = true, chart = null, saved = null) }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            syncQueue()
            val begin = leadInFrom(s.from, s.window).atStartOfDay()
            val end = s.to.plusDays(1).atStartOfDay()
            try {
                val entries = toolsWindow(begin, end)
                val points = rollingTrend(
                    entries = entries,
                    activityId = activityId,
                    metric = s.metric,
                    from = s.from,
                    to = s.to,
                    window = s.window,
                    nowMillis = System.currentTimeMillis(),
                )
                updateTrend {
                    it.copy(
                        running = false,
                        chart = trendChart(
                            points = points,
                            metric = s.metric,
                            window = s.window,
                            from = s.from,
                            to = s.to,
                            title = activityName(activityId),
                            activityId = activityId,
                        ),
                    )
                }
            } catch (e: Exception) {
                updateTrend { it.copy(running = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    fun exportTrend(format: ChartFormat, transparent: Boolean, seriesArgb: Int) {
        val s = _tools.value.trend
        val chart = s.chart ?: return
        updateTrend { it.copy(exporting = true, saved = null) }
        viewModelScope.launch {
            val name = "trend-${slug(chart.title)}-${s.window}d-${s.from}_${s.to}." +
                format.extension
            try {
                val where = ExportStore.save(ctx, name, format.mime) { out ->
                    ChartImage.writeTrend(chart, seriesArgb, format, transparent, out)
                }
                updateTrend { it.copy(exporting = false, saved = where) }
            } catch (e: Exception) {
                updateTrend { it.copy(exporting = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    private fun updateHeat(block: (HeatState) -> HeatState) {
        _tools.value = _tools.value.copy(heat = block(_tools.value.heat))
    }

    fun setHeatActivity(id: Int?) {
        updateHeat { it.copy(activityId = id, chart = null, saved = null, colorOverride = null) }
        loadHeat()
    }

    /** Paging never goes past this month: there is nothing there to show yet. */
    fun shiftHeatMonth(months: Long) {
        val next = _tools.value.heat.month.plusMonths(months)
        if (next.isAfter(YearMonth.now())) return
        updateHeat { it.copy(month = next, chart = null, saved = null) }
        loadHeat()
    }

    fun setHeatColor(hex: String?) = updateHeat { it.copy(colorOverride = hex, saved = null) }

    fun loadHeat() {
        val s = _tools.value.heat
        val activityId = s.activityId ?: return
        updateHeat { it.copy(running = true) }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            syncQueue()
            val begin = s.month.atDay(1).atStartOfDay()
            val end = s.month.plusMonths(1).atDay(1).atStartOfDay()
            try {
                val entries = toolsWindow(begin, end)
                updateHeat {
                    it.copy(
                        running = false,
                        chart = monthChart(
                            entries = entries,
                            activityId = activityId,
                            month = s.month,
                            title = activityName(activityId),
                            today = LocalDate.now(),
                            nowMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (e: Exception) {
                updateHeat { it.copy(running = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    fun exportHeat(format: ChartFormat, transparent: Boolean, seriesArgb: Int) {
        val s = _tools.value.heat
        val chart = s.chart ?: return
        updateHeat { it.copy(exporting = true, saved = null) }
        viewModelScope.launch {
            val name = "calendar-${slug(chart.title)}-${s.month}.${format.extension}"
            try {
                val where = ExportStore.save(ctx, name, format.mime) { out ->
                    ChartImage.writeMonth(chart, seriesArgb, format, transparent, out)
                }
                updateHeat { it.copy(exporting = false, saved = where) }
            } catch (e: Exception) {
                updateHeat { it.copy(exporting = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    private fun activityName(id: Int): String =
        _tools.value.activities.firstOrNull { it.id == id }?.name ?: "Activity #$id"

    /** An activity name as a filename: lower case, one dash for anything odd. */
    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "activity" }

    // ---------------- Import ----------------

    private fun updateImport(block: (ImportState) -> ImportState) {
        _tools.value = _tools.value.copy(import = block(_tools.value.import))
    }

    fun clearImport() = updateImport { ImportState() }

    /**
     * Read a CSV or Excel timesheet the user picked and fold it into the server.
     *
     * The file is only ever added to what is already there: an entry the server
     * already holds is counted and left alone, and an activity is created only
     * when nothing of that name exists yet — matched without regard to case, so
     * an export that has been through a spreadsheet doesn't quietly grow a
     * second "Reading". Nothing is deleted or edited, so importing the wrong
     * file costs at most a few entries to remove afterwards.
     *
     * This needs the server. The offline queue only carries starts and stops,
     * and a file's worth of history has no business being replayed as either.
     */
    fun runImport(uri: Uri) {
        if (prefs.serverless) return
        updateImport { it.copy(running = true, result = null, fileName = displayName(uri)) }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalArgumentException("That file couldn't be opened.")
                val read = readEntries(readTable(bytes))
                if (read.entries.isEmpty()) {
                    finishImport(
                    ImportResult(
                        fileName = fileNameOrFile(), added = 0, duplicates = 0,
                        activitiesCreated = 0, ignored = read.ignored,
                    )
                )
                    return@launch
                }
                syncQueue()
                importEntries(read)
            } catch (e: Exception) {
                updateImport { it.copy(running = false) }
                _tools.value = _tools.value.copy(error = friendly(e))
            }
        }
    }

    /** Create what's missing, skip what isn't, and re-read the window after. */
    private suspend fun importEntries(read: ImportRead) {
        // Hidden activities count too: a name that exists but isn't offered is
        // still that activity, and creating a second one would be the duplicate
        // this is meant to avoid.
        val activities = api().activities()
        val from = read.entries.minOf { it.begin }.toLocalDate().atStartOfDay()
        val to = read.entries.maxOf { it.end }.toLocalDate().plusDays(1).atStartOfDay()
        val existing = api().timesheets(formatKimai(from), formatKimai(to))
        val plan = planImport(read.entries, existing, activities)

        val byName = activities.associateByTo(mutableMapOf()) { it.name.trim().lowercase() }
        var created = 0
        plan.newActivities.forEach { name ->
            val made = api().createActivity(ActivityCreate(name = name, project = prefs.projectId))
            byName[name.trim().lowercase()] = made
            created++
        }
        val tagsMade = createMissingTags(plan.entries)

        var added = 0
        var refused = 0
        var why: String? = null
        plan.entries.forEach { entry ->
            val activityId = byName[entry.activity.trim().lowercase()]?.id ?: return@forEach
            val tags = entry.tags.joinToString(",").ifBlank { null }
            try {
                // Created with its end already on it — see [TimesheetCreate].
                api().createTimesheet(
                    TimesheetCreate(
                        begin = formatKimai(entry.begin),
                        project = prefs.projectId,
                        activity = activityId,
                        end = formatKimai(entry.end),
                        description = entry.description,
                        tags = tags,
                    )
                )
                added++
            } catch (e: Exception) {
                if (unreachable(e)) throw e
                // Kimai refuses entries that overlap a timer, among other things;
                // one bad row shouldn't cost the user the rest of the file.
                refused++
                if (why == null) why = friendly(e)
            }
        }

        // Pull the window back down so every other screen — and the offline copy
        // — shows what was just added.
        runCatching {
            val fresh = api().timesheets(formatKimai(from), formatKimai(to))
            cache.save(from, to, fresh, activities.filter { it.visible })
        }
        finishImport(
            ImportResult(
                fileName = fileNameOrFile(),
                added = added,
                duplicates = plan.duplicates,
                activitiesCreated = created,
                tagsCreated = tagsMade,
                ignored = read.ignored,
                refused = refused,
                refusedWhy = why,
            )
        )
    }

    /**
     * Make sure every tag the file uses exists before the entries do.
     *
     * Kimai attaches the tags it recognises and drops the rest without a word,
     * so a tag that has never been used here would vanish between the file and
     * the server. Creating one may not be allowed — the token's user needs the
     * permission — in which case nothing is lost that wasn't already going to
     * be, and the import carries on.
     */
    private suspend fun createMissingTags(entries: List<ImportEntry>): Int {
        val known = runCatching { api().tags() }.getOrDefault(emptyList())
        val missing = entries
            .flatMap { it.tags }
            .distinctBy { it.lowercase() }
            .filter { tag -> known.none { it.equals(tag, ignoreCase = true) } }
        var made = 0
        missing.forEach { tag ->
            runCatching { api().createTag(TagCreate(tag)) }.onSuccess { made++ }
        }
        return made
    }

    private fun fileNameOrFile(): String = _tools.value.import.fileName ?: "the file"

    private suspend fun finishImport(result: ImportResult) {
        updateImport { it.copy(running = false, result = result) }
        if (result.added > 0 || result.activitiesCreated > 0) {
            loadTools()
            refresh()
        }
    }

    /** What the picked document calls itself, for the report afterwards. */
    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (_: Exception) {
        null
    }

    // ---------------- Batch edit ----------------

    private fun updateBatch(block: (BatchState) -> BatchState) {
        _tools.value = _tools.value.copy(batch = block(_tools.value.batch))
    }

    /** Any filter change invalidates the previous match set. */
    private fun refilter(block: (BatchState) -> BatchState) = updateBatch {
        block(it).copy(searched = false, matches = emptyList(), done = null)
    }

    fun setBatchActivity(id: Int?) = refilter { it.copy(filterActivityId = id) }
    fun setBatchTag(tag: String?) = refilter { it.copy(filterTag = tag) }
    fun setBatchMin(v: String) = refilter { it.copy(minMinutes = v.filter(Char::isDigit)) }
    fun setBatchMax(v: String) = refilter { it.copy(maxMinutes = v.filter(Char::isDigit)) }
    fun setBatchFrom(d: LocalDate) = refilter { it.copy(from = d) }
    fun setBatchTo(d: LocalDate) = refilter { it.copy(to = d) }

    /** Pull the window from the server and keep the entries the filters allow. */
    fun runBatchSearch() {
        val b = _tools.value.batch
        if (b.to.isBefore(b.from)) {
            _tools.value = _tools.value.copy(error = "End date is before the start date.")
            return
        }
        updateBatch { it.copy(searching = true, done = null) }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            val begin = b.from.atStartOfDay()
            val end = b.to.plusDays(1).atStartOfDay()
            try {
                val entries = api().timesheets(
                    begin = formatKimai(begin), end = formatKimai(end),
                )
                cache.save(begin, end, entries, emptyList())
                _tools.value = _tools.value.copy(cached = null)
                updateBatch {
                    it.copy(
                        searching = false, searched = true,
                        matches = entries.filter { e -> batchMatches(e, b) },
                    )
                }
            } catch (e: Exception) {
                val saved = fallback(e)
                if (saved == null) {
                    _tools.value = _tools.value.copy(error = friendly(e))
                    updateBatch { it.copy(searching = false) }
                } else {
                    _tools.value = _tools.value.copy(cached = saved.second)
                    updateBatch {
                        it.copy(
                            searching = false, searched = true,
                            matches = saved.first.between(begin, end)
                                .filter { e -> batchMatches(e, b) },
                        )
                    }
                }
            }
        }
    }

    private fun batchMatches(e: TimesheetEntry, b: BatchState): Boolean {
        if (b.filterActivityId != null && e.activity != b.filterActivityId) return false
        b.filterTag?.let { wanted ->
            val tags = e.tags?.filter { it.isNotBlank() }.orEmpty()
            val ok = if (wanted == UNTAGGED) tags.isEmpty() else tags.contains(wanted)
            if (!ok) return false
        }
        val minutes = entrySeconds(e.begin, e.end, e.duration, System.currentTimeMillis()) / 60
        b.minMinutes.toLongOrNull()?.let { if (minutes < it) return false }
        b.maxMinutes.toLongOrNull()?.let { if (minutes > it) return false }
        return true
    }

    /** Arm an action; nothing is sent until it's confirmed. */
    fun askBatch(
        action: BatchAction,
        activityId: Int? = null,
        tags: List<String> = emptyList(),
        color: String? = null,
        activityName: String = "",
    ) = updateBatch {
        it.copy(
            pending = action, pendingActivityId = activityId,
            pendingActivityName = activityName,
            pendingTags = tags, pendingColor = color, confirmedOnce = false,
        )
    }

    /**
     * The activity is changed by typing a name, not by picking one.
     *
     * A name nothing else uses renames the activity the matched entries belong
     * to — which needs them to belong to exactly one. A name that is already
     * taken cannot be created twice, so the only sensible reading is "put these
     * entries under that activity", and the user is asked before that happens.
     */
    fun askBatchActivityName(raw: String) {
        val name = raw.trim()
        if (name.isEmpty()) return
        val distinct = _tools.value.batch.matches.map { it.activity }.distinct()
        if (distinct.isEmpty()) return
        val existing = _tools.value.activities
            .firstOrNull { it.name.equals(name, ignoreCase = true) }

        if (existing == null) {
            if (distinct.size != 1) {
                _tools.value = _tools.value.copy(
                    error = "These entries span ${distinct.size} activities, so there's " +
                        "no single one to rename. Filter to one activity first.",
                )
                return
            }
            askBatch(BatchAction.RENAME_ACTIVITY, activityId = distinct.first(), activityName = name)
            return
        }
        if (distinct.size == 1 && distinct.first() == existing.id) {
            _tools.value = _tools.value.copy(
                error = "These entries are already under “${existing.name}”.",
            )
            return
        }
        askBatch(BatchAction.MOVE_ACTIVITY, activityId = existing.id, activityName = existing.name)
    }

    fun dismissBatch() = updateBatch { it.copy(pending = null, confirmedOnce = false) }

    /** Deleting is the one action that has to be confirmed twice. */
    fun confirmBatch() {
        val b = _tools.value.batch
        if (b.pending == BatchAction.DELETE && !b.confirmedOnce) {
            updateBatch { it.copy(confirmedOnce = true) }
            return
        }
        applyBatch()
    }

    private fun applyBatch() {
        val b = _tools.value.batch
        val action = b.pending ?: return
        val targets = b.matches
        if (targets.isEmpty()) {
            dismissBatch()
            return
        }
        updateBatch {
            it.copy(pending = null, confirmedOnce = false, applying = true, progress = "0 of ${targets.size}")
        }
        _tools.value = _tools.value.copy(error = null)
        viewModelScope.launch {
            try {
                val summary = when (action) {
                    // Renaming touches one row: the activity itself.
                    BatchAction.RENAME_ACTIVITY -> {
                        val id = b.pendingActivityId ?: return@launch dismissBatch()
                        api().updateActivityName(id, ActivityNameUpdate(name = b.pendingActivityName))
                        // The stored list still holds the old name.
                        _tools.value = _tools.value.copy(
                            activities = api().activities()
                                .filter { it.visible }.sortedBy { it.name.lowercase() },
                        )
                        "Renamed the activity to “${b.pendingActivityName}”."
                    }
                    // Colour belongs to the activity, so it's applied once per
                    // distinct activity rather than once per entry.
                    BatchAction.SET_COLOR -> {
                        val color = b.pendingColor ?: return@launch dismissBatch()
                        val ids = targets.map { it.activity }.distinct()
                        ids.forEachIndexed { i, id ->
                            updateBatch { it.copy(progress = "${i + 1} of ${ids.size} activities") }
                            api().updateActivityColor(id, ActivityColorUpdate(color = color))
                        }
                        "Recoloured ${ids.size} ${plural(ids.size, "activity", "activities")}."
                    }
                    else -> {
                        targets.forEachIndexed { i, e ->
                            updateBatch { it.copy(progress = "${i + 1} of ${targets.size}") }
                            when (action) {
                                BatchAction.DELETE -> {
                                    api().deleteTimesheet(e.id)
                                    cache.remove(e.id)
                                }
                                BatchAction.MOVE_ACTIVITY -> api().updateTimesheet(
                                    e.id, patchFor(e, activity = b.pendingActivityId),
                                )
                                BatchAction.SET_TAGS -> api().updateTimesheet(
                                    e.id, patchFor(e, tags = b.pendingTags.joinToString(",")),
                                )
                                BatchAction.SET_COLOR,
                                BatchAction.RENAME_ACTIVITY -> Unit   // handled above
                            }
                        }
                        val n = targets.size
                        val what = plural(n, "entry", "entries")
                        when (action) {
                            BatchAction.DELETE -> "Deleted $n $what."
                            BatchAction.MOVE_ACTIVITY ->
                                "Moved $n $what to “${b.pendingActivityName}”."
                            else -> "Retagged $n $what."
                        }
                    }
                }
                // The match set is stale now; make the user search again rather
                // than showing entries that no longer look like that.
                updateBatch {
                    it.copy(
                        applying = false, progress = null, done = summary,
                        matches = emptyList(), searched = false,
                    )
                }
            } catch (e: Exception) {
                _tools.value = _tools.value.copy(error = friendly(e))
                updateBatch { it.copy(applying = false, progress = null) }
            }
        }
    }

    /**
     * A PATCH body carrying the entry's own begin/end so a partial update can't
     * disturb them; everything left null is untouched by Kimai.
     */
    private fun patchFor(
        e: TimesheetEntry,
        activity: Int? = null,
        tags: String? = null,
    ): TimesheetUpdate = TimesheetUpdate(
        begin = parseKimaiLocal(e.begin)?.let(::formatKimai) ?: e.begin,
        end = e.end?.let { iso -> parseKimaiLocal(iso)?.let(::formatKimai) ?: iso },
        activity = activity,
        tags = tags,
    )

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    // ---------------- Visualisations ----------------

    fun setVizTab(tab: VizTab) { _viz.value = _viz.value.copy(tab = tab) }

    fun setPieMode(mode: PieMode) { _viz.value = _viz.value.copy(pieMode = mode) }

    /** Changing the granularity drops back to the present period. */
    fun setPeriod(period: VizPeriod) {
        _viz.value = _viz.value.copy(period = period, pieOffset = 0)
        loadViz()
    }

    /** Page the pie one period back ([delta] < 0) or forward; never past the present. */
    fun shiftPie(delta: Int) {
        val next = (_viz.value.pieOffset + delta).coerceAtMost(0)
        if (next == _viz.value.pieOffset) return
        _viz.value = _viz.value.copy(pieOffset = next)
        loadViz()
    }

    fun pieToday() {
        if (_viz.value.pieOffset == 0) return
        _viz.value = _viz.value.copy(pieOffset = 0)
        loadViz()
    }

    /** Fetch everything the viz screen needs, falling back to saved data. */
    fun loadViz() {
        _viz.value = _viz.value.copy(loading = true, error = null)
        viewModelScope.launch {
            syncQueue()
            val today = LocalDate.now()
            val (pieFrom, pieTo) = pieRange(_viz.value.period, _viz.value.pieOffset, today)
            val pieBegin = pieFrom.atStartOfDay()
            val pieEnd = pieTo.plusDays(1).atStartOfDay()
            val barBegin = today.minusDays(29).atStartOfDay()
            val end = today.plusDays(1).atStartOfDay()
            try {
                val acts = api().activities()
                val pie = api().timesheets(
                    begin = formatKimai(pieBegin), end = formatKimai(pieEnd),
                )
                val bar = api().timesheets(
                    begin = formatKimai(barBegin), end = formatKimai(end),
                )
                cache.save(pieBegin, pieEnd, pie, acts)
                cache.save(barBegin, end, bar, acts)
                _viz.value = _viz.value.copy(
                    loading = false,
                    pieEntries = merged(pie, pieBegin, pieEnd),
                    barEntries = merged(bar, barBegin, end),
                    activities = acts, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _viz.value = if (saved == null) {
                    _viz.value.copy(loading = false, error = friendly(e))
                } else {
                    _viz.value.copy(
                        loading = false,
                        pieEntries = merged(
                            saved.first.between(pieBegin, pieEnd), pieBegin, pieEnd,
                        ),
                        barEntries = merged(
                            saved.first.between(barBegin, end), barBegin, end,
                        ),
                        activities = saved.first.activities,
                        cached = saved.second,
                    )
                }
            }
        }
    }

    // ---------------- Timesheet ----------------

    fun loadSheet() {
        _sheet.value = _sheet.value.copy(loading = true, error = null)
        viewModelScope.launch {
            syncQueue()
            val today = LocalDate.now()
            val begin = today.minusDays(365).atStartOfDay()
            val end = today.plusDays(1).atStartOfDay()
            try {
                val entries = api().timesheets(
                    begin = formatKimai(begin), end = formatKimai(end),
                )
                val acts = api().activities()
                val colors = try { api().configColors() } catch (e: Exception) { _sheet.value.colorChoices }
                val tags = try { api().tags() } catch (e: Exception) { _sheet.value.allTags }
                cache.save(begin, end, entries, acts)
                _sheet.value = _sheet.value.copy(
                    loading = false, offline = false,
                    entries = merged(entries, begin, end), activities = acts,
                    colorChoices = colors, allTags = tags, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _sheet.value = if (saved == null) {
                    _sheet.value.copy(
                        loading = false, offline = canQueue(e), error = friendly(e),
                    )
                } else {
                    _sheet.value.copy(
                        loading = false,
                        offline = canQueue(e),
                        entries = merged(saved.first.between(begin, end), begin, end),
                        activities = saved.first.activities,
                        cached = saved.second,
                    )
                }
            }
        }
    }

    fun openEdit(entry: TimesheetEntry) { _sheet.value = _sheet.value.copy(editing = entry) }
    fun dismissEdit() { _sheet.value = _sheet.value.copy(editing = null) }

    // ---------------- Delete ----------------

    /** Second step of a swipe-to-delete: ask before anything leaves the server. */
    fun askDelete(entry: TimesheetEntry) {
        _sheet.value = _sheet.value.copy(pendingDelete = entry, error = null)
    }

    fun dismissDelete() { _sheet.value = _sheet.value.copy(pendingDelete = null) }

    /** Final step: remove the entry from the server, the list and the cache. */
    fun deleteEntry(entryId: Int) {
        val wasRunning = _sheet.value.entries.firstOrNull { it.id == entryId }?.end == null
        _sheet.value = _sheet.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            // A queued entry only ever existed here, so dropping it is the whole
            // of the deletion — there is nothing to tell the server about.
            if (isQueued(entryId)) {
                pending.removeStart(entryId)
                readQueue()
                _sheet.value = _sheet.value.copy(
                    deleting = false,
                    pendingDelete = null,
                    entries = _sheet.value.entries.filterNot { it.id == entryId },
                )
                loadSheet()
                if (wasRunning) refresh()
                return@launch
            }
            try {
                api().deleteTimesheet(entryId)
                cache.remove(entryId)
                _sheet.value = _sheet.value.copy(
                    deleting = false,
                    pendingDelete = null,
                    entries = _sheet.value.entries.filterNot { it.id == entryId },
                )
                loadSheet()
                // The timer screen was showing this entry as active; re-read it.
                if (wasRunning) refresh()
            } catch (e: Exception) {
                _sheet.value = _sheet.value.copy(deleting = false, error = friendly(e))
            }
        }
    }

    // ---------------- Timesheet filters ----------------

    fun setSheetActivityFilter(id: Int?) {
        // Picking from the menu replaces a multi-activity filter outright.
        _sheet.value = _sheet.value.copy(filterActivityId = id, filterActivityIds = null)
    }

    fun setSheetTagFilter(tag: String?) {
        _sheet.value = _sheet.value.copy(filterTag = tag)
    }

    /** Apply a calendar preset (today / this week / this month / this year / all). */
    fun setSheetPeriod(preset: SheetPeriod) {
        val today = LocalDate.now()
        val from = when (preset) {
            SheetPeriod.ALL, SheetPeriod.CUSTOM -> null
            SheetPeriod.DAY -> today
            SheetPeriod.WEEK -> today.with(DayOfWeek.MONDAY)
            SheetPeriod.MONTH -> today.withDayOfMonth(1)
            SheetPeriod.YEAR -> today.withDayOfYear(1)
        }
        _sheet.value = _sheet.value.copy(
            filterPreset = if (preset == SheetPeriod.CUSTOM) SheetPeriod.ALL else preset,
            filterFrom = from,
            filterTo = if (from == null) null else today,
        )
    }

    /** Filter to one specific calendar day. */
    fun setSheetDate(date: LocalDate) {
        _sheet.value = _sheet.value.copy(
            filterPreset = SheetPeriod.CUSTOM, filterFrom = date, filterTo = date,
        )
    }

    fun clearSheetFilters() {
        _sheet.value = _sheet.value.copy(
            filterActivityId = null, filterActivityIds = null, filterTag = null,
            filterFrom = null, filterTo = null, filterPreset = SheetPeriod.ALL,
        )
    }

    /**
     * Jump from a chart to the timesheet with the clicked activity/tag and the
     * chart's visible date window pre-applied. [activityIds] carries the several
     * activities behind an "Other" wedge, in place of a single one.
     */
    fun openSheetFiltered(
        activityId: Int?,
        tag: String?,
        from: LocalDate,
        to: LocalDate,
        activityIds: List<Int>? = null,
    ) {
        _sheet.value = _sheet.value.copy(
            filterActivityId = activityId, filterActivityIds = activityIds, filterTag = tag,
            filterFrom = from, filterTo = to, filterPreset = SheetPeriod.CUSTOM,
        )
        navigate(AppScreen.SHEET)
    }

    /**
     * Persist an edit: optionally recolor the activity (server-wide), then
     * update the entry's begin/end/description/tags. A null [endIso] leaves the
     * end untouched (running entries stay running unless an end is explicitly
     * set). description and tags are always sent — empty strings clear them.
     */
    fun saveEdit(
        entryId: Int,
        activityId: Int,
        beginIso: String,
        endIso: String?,
        newColor: String?,
        description: String,
        tags: String,
    ) {
        _sheet.value = _sheet.value.copy(saving = true, error = null)
        viewModelScope.launch {
            // A queued entry is edited in the queue: its times are the only
            // thing about it that can be changed before it is sent.
            if (isQueued(entryId)) {
                pending.retime(entryId, beginIso, endIso)
                readQueue()
                _sheet.value = _sheet.value.copy(saving = false, editing = null)
                loadSheet()
                refresh()
                return@launch
            }
            try {
                if (newColor != null) {
                    api().updateActivityColor(activityId, ActivityColorUpdate(color = newColor))
                }
                api().updateTimesheet(
                    entryId,
                    TimesheetUpdate(
                        begin = beginIso, end = endIso,
                        description = description, tags = tags,
                    ),
                )
                _sheet.value = _sheet.value.copy(saving = false, editing = null)
                loadSheet()
            } catch (e: Exception) {
                _sheet.value = _sheet.value.copy(saving = false, error = friendly(e))
            }
        }
    }

    fun clearSheetError() { _sheet.value = _sheet.value.copy(error = null) }
    fun clearVizError() { _viz.value = _viz.value.copy(error = null) }

    /**
     * Locally saved data to render when a fetch fails, paired with the note the
     * UI shows so the numbers are never mistaken for live ones. Null when
     * nothing has ever been downloaded — the caller should surface [e] instead.
     */
    private suspend fun fallback(e: Exception): Pair<CacheSnapshot, CacheInfo>? {
        val snap = cache.snapshot()
        if (snap.lastSync == 0L) return null
        return snap to CacheInfo(snap.lastSync, friendly(e))
    }

    private fun friendly(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("Failed to connect", true) ||
                msg.contains("Unable to resolve", true) ||
                msg.contains("timeout", true) -> "Cannot reach server. Check the URL and that you're on the same network."
            msg.contains("401") || msg.contains("403") -> "Authentication failed. Check your API token."
            else -> msg
        }
    }
}
