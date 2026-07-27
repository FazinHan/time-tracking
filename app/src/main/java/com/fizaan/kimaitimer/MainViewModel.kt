package com.fizaan.kimaitimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.ActivityColorUpdate
import com.fizaan.kimaitimer.data.ActivityCreate
import com.fizaan.kimaitimer.data.ActivityNameUpdate
import com.fizaan.kimaitimer.data.ApiProvider
import com.fizaan.kimaitimer.data.CacheSnapshot
import com.fizaan.kimaitimer.data.Customer
import com.fizaan.kimaitimer.data.Prefs
import com.fizaan.kimaitimer.data.Project
import com.fizaan.kimaitimer.data.TimesheetActive
import com.fizaan.kimaitimer.data.TimesheetCache
import com.fizaan.kimaitimer.data.TimesheetCreate
import com.fizaan.kimaitimer.data.TimesheetEntry
import com.fizaan.kimaitimer.data.TimesheetUpdate
import androidx.core.app.NotificationManagerCompat
import com.fizaan.kimaitimer.pomodoro.ALERT_NOTIFICATION_ID
import com.fizaan.kimaitimer.pomodoro.Phase
import com.fizaan.kimaitimer.pomodoro.PomodoroAlarm
import com.fizaan.kimaitimer.pomodoro.PomodoroSettings
import com.fizaan.kimaitimer.pomodoro.sessionSummary
import com.fizaan.kimaitimer.util.entryLocalDate
import com.fizaan.kimaitimer.util.entrySeconds
import com.fizaan.kimaitimer.util.formatKimai
import com.fizaan.kimaitimer.util.parseKimaiLocal
import com.fizaan.kimaitimer.util.parseKimaiMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AppScreen { TIMER, POMODORO, VIZ, SHEET, CALENDAR, TOOLS }
enum class VizTab { PIE, BAR }
enum class PieMode { ACTIVITY, TAG }
enum class VizPeriod { DAY, WEEK, MONTH, YEAR }
enum class SheetPeriod { ALL, DAY, WEEK, MONTH, YEAR, CUSTOM }

/** Sentinel tag filter meaning "entries with no tags". */
const val UNTAGGED = ""

/** The tag that qualifies an activity for a pomodoro. */
const val PRODUCTIVE_TAG = "productive"

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
 * Result of the frequency tool. Each frequency is sessions ÷ the number of
 * distinct *active* periods of that granularity (days/weeks/months/years that
 * actually had a session), so empty periods never dilute the count. Time spent
 * is reported separately as a full-year projection over the selected span.
 */
data class FreqResult(
    val activityId: Int,
    val from: LocalDate,
    val to: LocalDate,
    val sessions: Int,
    val totalSeconds: Long,
    val activeDays: Int,
    val activeWeeks: Int,
    val activeMonths: Int,
    val activeYears: Int,
)

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

/** Tools screen state. Currently hosts the frequency calculator. */
data class ToolsState(
    val loading: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    val computing: Boolean = false,
    val cacheBytes: Long = 0L,
    val cacheEntries: Int = 0,
    val activities: List<Activity> = emptyList(),
    val freqActivityId: Int? = null,
    val freqFrom: LocalDate = LocalDate.now().minusDays(29),
    val freqTo: LocalDate = LocalDate.now(),
    val freqResult: FreqResult? = null,
    val allTags: List<String> = emptyList(),
    val colorChoices: Map<String, String> = emptyMap(),
    val batch: BatchState = BatchState(),
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
    val activities: List<Activity> = emptyList(),   // only the productive-tagged ones
    val startMs: Long = 0L,
    val entryId: Int? = null,
    val activityName: String = "",
    val showPicker: Boolean = false,
    val showSettings: Boolean = false,
    val alert: Phase? = null,                       // a boundary just passed
) {
    val running: Boolean get() = startMs > 0L && entryId != null
}

/** Whole-app UI state. */
data class UiState(
    val configured: Boolean = false,
    val screen: AppScreen = AppScreen.TIMER,
    val loading: Boolean = false,
    val busy: Boolean = false,          // an action (start/stop/create) is in flight
    val error: String? = null,
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
    private val notifier = RunningNotifier(app)
    private val beginFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _setup = MutableStateFlow(SetupState(baseUrl = defaultUrlHint()))
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
            startMs = prefs.pomodoroStartMs,
            entryId = prefs.pomodoroEntryId.takeIf { it >= 0 },
            activityName = prefs.pomodoroActivityName,
        )
    )
    val pomodoro: StateFlow<PomodoroState> = _pomodoro.asStateFlow()

    init {
        if (prefs.isConfigured) {
            _ui.value = _ui.value.copy(configured = true, projectName = prefs.projectName)
            refresh()
        }
    }

    private fun defaultUrlHint(): String =
        if (prefs.baseUrl.isNotBlank()) prefs.baseUrl else "http://192.168.0.110:8000"

    private fun api() = ApiProvider.get(prefs)

    // ---------------- Setup flow ----------------

    fun onUrl(v: String) { _setup.value = _setup.value.copy(baseUrl = v) }
    fun onToken(v: String) { _setup.value = _setup.value.copy(token = v) }
    fun onLegacyUser(v: String) { _setup.value = _setup.value.copy(legacyUser = v) }
    fun onUseLegacy(v: Boolean) { _setup.value = _setup.value.copy(useLegacy = v) }
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
        )
        _ui.value = _ui.value.copy(configured = false)
    }

    // ---------------- Main flow ----------------

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // Oldest first, so the timer that started earlier keeps the big clock.
                val actives = api().active().sortedBy { parseKimaiMillis(it.begin) ?: 0L }
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                // "recent" is a nice-to-have; don't let it break the main screen.
                val recent = try { api().recent(8) } catch (e: Exception) { _ui.value.recent }
                // Tags are used to build the picker; best-effort like recent.
                val tags = try { api().tags() } catch (e: Exception) { _ui.value.allTags }
                _ui.value = _ui.value.copy(
                    loading = false,
                    running = actives.getOrNull(0),
                    second = actives.getOrNull(1),
                    activities = acts,
                    recent = recent, allTags = tags,
                )
                syncNotification()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = friendly(e))
            }
        }
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

    /** Resume a recent activity, carrying over its description and tags. */
    fun resume(item: TimesheetActive) {
        val activityId = item.activity?.id ?: return
        val tags = item.tags?.filter { it.isNotBlank() }.orEmpty()
        // Seed the on-device memory from a tagged entry, but don't let an
        // untagged resume permanently suppress the first-time prompt.
        if (tags.isNotEmpty()) prefs.setTag(activityId, tags.joinToString(","))
        start(activityId, description = item.description, tags = tags.joinToString(",").ifBlank { null })
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
                val begin = LocalDateTime.now().format(beginFormat)
                api().createTimesheet(
                    TimesheetCreate(
                        begin = begin,
                        project = prefs.projectId,
                        activity = activityId,
                        description = description?.ifBlank { null },
                        tags = tags?.ifBlank { null },
                    )
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
            try {
                api().stop(id)
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
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, error = friendly(e))
            }
        }
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
            startMs = prefs.pomodoroStartMs,
            entryId = prefs.pomodoroEntryId.takeIf { it >= 0 },
            activityName = prefs.pomodoroActivityName,
        )
        // An alarm may have been missed while the app was dead; re-arm the next.
        // With no session there is nothing to arm and nothing left to announce.
        if (prefs.pomodoroStartMs > 0L) PomodoroAlarm.scheduleNext(ctx)
        else PomodoroAlarm.cancel(ctx)
        viewModelScope.launch {
            try {
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                val today = LocalDate.now()
                val history = api().timesheets(
                    begin = formatKimai(today.minusDays(90).atStartOfDay()),
                    end = formatKimai(today.plusDays(1).atStartOfDay()),
                )
                // The session may have been stopped elsewhere (the timer screen,
                // Kimai itself); don't keep showing a clock for a dead entry.
                val id = _pomodoro.value.entryId
                if (id != null && api().active().none { it.id == id }) clearPomodoroSession()
                _pomodoro.value = _pomodoro.value.copy(
                    loading = false,
                    activities = productiveActivities(acts, history),
                )
            } catch (e: Exception) {
                _pomodoro.value = _pomodoro.value.copy(loading = false, error = friendly(e))
            }
        }
    }

    fun openPomodoroPicker() { _pomodoro.value = _pomodoro.value.copy(showPicker = true, error = null) }
    fun dismissPomodoroPicker() { _pomodoro.value = _pomodoro.value.copy(showPicker = false) }
    fun openPomodoroSettings() { _pomodoro.value = _pomodoro.value.copy(showSettings = true) }
    fun dismissPomodoroSettings() { _pomodoro.value = _pomodoro.value.copy(showSettings = false) }
    fun clearPomodoroError() { _pomodoro.value = _pomodoro.value.copy(error = null) }

    fun savePomodoroSettings(settings: PomodoroSettings) {
        prefs.pomodoroSettings = settings
        _pomodoro.value = _pomodoro.value.copy(
            settings = prefs.pomodoroSettings, showSettings = false,
        )
        // A running session's remaining boundaries move with the new lengths.
        if (_pomodoro.value.running) PomodoroAlarm.scheduleNext(ctx)
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
                val created = api().createTimesheet(
                    TimesheetCreate(
                        begin = beginIso,
                        project = prefs.projectId,
                        activity = activityId,
                        description = null,
                        tags = (prefs.tagFor(activityId)?.ifBlank { null } ?: PRODUCTIVE_TAG),
                    )
                )
                val startMs = begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                prefs.pomodoroStartMs = startMs
                prefs.pomodoroEntryId = created.id
                prefs.pomodoroActivityId = activityId
                prefs.pomodoroActivityName = name
                prefs.pomodoroBeginIso = beginIso
                _pomodoro.value = _pomodoro.value.copy(
                    busy = false, startMs = startMs, entryId = created.id,
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
            try {
                val summary = sessionSummary(s.startMs, System.currentTimeMillis(), s.settings)
                api().updateTimesheet(
                    entryId,
                    TimesheetUpdate(
                        begin = prefs.pomodoroBeginIso.ifBlank {
                            formatKimai(LocalDateTime.now())
                        },
                        description = summary,
                    ),
                )
                api().stop(entryId)
                clearPomodoroSession()
                _pomodoro.value = _pomodoro.value.copy(busy = false)
                refresh()
            } catch (e: Exception) {
                _pomodoro.value = _pomodoro.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    private fun clearPomodoroSession() {
        PomodoroAlarm.cancel(ctx)
        prefs.clearPomodoroSession()
        _pomodoro.value = _pomodoro.value.copy(
            startMs = 0L, entryId = null, activityName = "", alert = null,
        )
    }

    // ---------------- Calendar ----------------

    /** Fetch a padded window of entries around the calendar's anchor day. */
    fun loadCalendar() {
        _calendar.value = _calendar.value.copy(loading = true, error = null)
        viewModelScope.launch {
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
                    loading = false, entries = entries, activities = acts, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _calendar.value = if (saved == null) {
                    _calendar.value.copy(loading = false, error = friendly(e))
                } else {
                    _calendar.value.copy(
                        loading = false,
                        entries = saved.first.between(from, to),
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
            val stats = cache.stats()
            _tools.value = _tools.value.copy(
                cacheBytes = stats.bytes, cacheEntries = stats.entries,
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
            val begin = from.atStartOfDay()
            val end = to.plusDays(1).atStartOfDay()
            try {
                val entries = api().timesheets(
                    begin = formatKimai(begin), end = formatKimai(end),
                )
                cache.save(begin, end, entries, emptyList())
                _tools.value = _tools.value.copy(
                    computing = false, cached = null,
                    freqResult = summarise(activityId, from, to, entries),
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _tools.value = if (saved == null) {
                    _tools.value.copy(computing = false, error = friendly(e))
                } else {
                    _tools.value.copy(
                        computing = false, cached = saved.second,
                        freqResult = summarise(
                            activityId, from, to, saved.first.between(begin, end),
                        ),
                    )
                }
            }
            val stats = cache.stats()
            _tools.value = _tools.value.copy(
                cacheBytes = stats.bytes, cacheEntries = stats.entries,
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
            activeDays = dates.distinct().size,
            activeWeeks = dates.map { it.with(DayOfWeek.MONDAY) }.distinct().size,
            activeMonths = dates.map { it.withDayOfMonth(1) }.distinct().size,
            activeYears = dates.map { it.year }.distinct().size,
        )
    }

    fun clearToolsError() { _tools.value = _tools.value.copy(error = null) }

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
                    loading = false, pieEntries = pie, barEntries = bar,
                    activities = acts, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _viz.value = if (saved == null) {
                    _viz.value.copy(loading = false, error = friendly(e))
                } else {
                    _viz.value.copy(
                        loading = false,
                        pieEntries = saved.first.between(pieBegin, pieEnd),
                        barEntries = saved.first.between(barBegin, end),
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
                    loading = false, entries = entries, activities = acts,
                    colorChoices = colors, allTags = tags, cached = null,
                )
            } catch (e: Exception) {
                val saved = fallback(e)
                _sheet.value = if (saved == null) {
                    _sheet.value.copy(loading = false, error = friendly(e))
                } else {
                    _sheet.value.copy(
                        loading = false,
                        entries = saved.first.between(begin, end),
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
        _sheet.value = _sheet.value.copy(filterActivityId = id)
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
            filterActivityId = null, filterTag = null,
            filterFrom = null, filterTo = null, filterPreset = SheetPeriod.ALL,
        )
    }

    /**
     * Jump from a chart legend to the timesheet with the clicked activity/tag
     * and the chart's visible date window pre-applied.
     */
    fun openSheetFiltered(activityId: Int?, tag: String?, from: LocalDate, to: LocalDate) {
        _sheet.value = _sheet.value.copy(
            filterActivityId = activityId, filterTag = tag,
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
