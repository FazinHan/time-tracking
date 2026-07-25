package com.fizaan.kimaitimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.ActivityColorUpdate
import com.fizaan.kimaitimer.data.ActivityCreate
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
import com.fizaan.kimaitimer.util.entryLocalDate
import com.fizaan.kimaitimer.util.entrySeconds
import com.fizaan.kimaitimer.util.formatKimai
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class AppScreen { TIMER, VIZ, SHEET, CALENDAR, TOOLS }
enum class VizTab { PIE, BAR }
enum class PieMode { ACTIVITY, TAG }
enum class VizPeriod { DAY, WEEK, MONTH, YEAR }
enum class SheetPeriod { ALL, DAY, WEEK, MONTH, YEAR, CUSTOM }

/** Sentinel tag filter meaning "entries with no tags". */
const val UNTAGGED = ""

/**
 * Marks a screen as rendering locally saved data instead of a live server
 * response. [savedAt] is when that data was last downloaded, [reason] why the
 * server couldn't be reached.
 */
data class CacheInfo(val savedAt: Long, val reason: String)

/** Visualisation screen state. Entries are refetched on every open/change. */
data class VizState(
    val loading: Boolean = false,
    val error: String? = null,
    val cached: CacheInfo? = null,
    val tab: VizTab = VizTab.PIE,
    val pieMode: PieMode = PieMode.ACTIVITY,
    val period: VizPeriod = VizPeriod.DAY,
    val pieEntries: List<TimesheetEntry> = emptyList(),
    val barEntries: List<TimesheetEntry> = emptyList(),   // last 30 days
    val activities: List<Activity> = emptyList(),
)

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
)

/** Whole-app UI state. */
data class UiState(
    val configured: Boolean = false,
    val screen: AppScreen = AppScreen.TIMER,
    val loading: Boolean = false,
    val busy: Boolean = false,          // an action (start/stop/create) is in flight
    val error: String? = null,
    val running: TimesheetActive? = null,
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
    private val prefs = Prefs(app)
    private val cache = TimesheetCache(app)
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
                val active = api().active().firstOrNull()
                val acts = api().activities().filter { it.visible }.sortedBy { it.name.lowercase() }
                // "recent" is a nice-to-have; don't let it break the main screen.
                val recent = try { api().recent(8) } catch (e: Exception) { _ui.value.recent }
                // Tags are used to build the picker; best-effort like recent.
                val tags = try { api().tags() } catch (e: Exception) { _ui.value.allTags }
                _ui.value = _ui.value.copy(
                    loading = false, running = active, activities = acts,
                    recent = recent, allTags = tags,
                )
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
                _ui.value = _ui.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    fun stop() {
        val id = _ui.value.running?.id ?: return
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                api().stop(id)
                _ui.value = _ui.value.copy(busy = false, running = null)
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
            AppScreen.VIZ -> loadViz()
            AppScreen.SHEET -> loadSheet()
            AppScreen.CALENDAR -> loadCalendar()
            AppScreen.TOOLS -> loadTools()
        }
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
                _tools.value = _tools.value.copy(loading = false, activities = acts, cached = null)
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

    // ---------------- Visualisations ----------------

    fun setVizTab(tab: VizTab) { _viz.value = _viz.value.copy(tab = tab) }

    fun setPieMode(mode: PieMode) { _viz.value = _viz.value.copy(pieMode = mode) }

    fun setPeriod(period: VizPeriod) {
        _viz.value = _viz.value.copy(period = period)
        loadViz()
    }

    /** Period start for the pie query (calendar day / ISO week / month / year). */
    private fun periodStart(period: VizPeriod, today: LocalDate): LocalDate = when (period) {
        VizPeriod.DAY -> today
        VizPeriod.WEEK -> today.with(DayOfWeek.MONDAY)
        VizPeriod.MONTH -> today.withDayOfMonth(1)
        VizPeriod.YEAR -> today.withDayOfYear(1)
    }

    /** Fetch everything the viz screen needs, falling back to saved data. */
    fun loadViz() {
        _viz.value = _viz.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val today = LocalDate.now()
            val pieBegin = periodStart(_viz.value.period, today).atStartOfDay()
            val barBegin = today.minusDays(29).atStartOfDay()
            val end = today.plusDays(1).atStartOfDay()
            try {
                val acts = api().activities()
                val pie = api().timesheets(
                    begin = formatKimai(pieBegin), end = formatKimai(end),
                )
                val bar = api().timesheets(
                    begin = formatKimai(barBegin), end = formatKimai(end),
                )
                cache.save(pieBegin, end, pie, acts)
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
                        pieEntries = saved.first.between(pieBegin, end),
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
