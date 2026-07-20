package com.fizaan.kimaitimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fizaan.kimaitimer.data.Activity
import com.fizaan.kimaitimer.data.ActivityCreate
import com.fizaan.kimaitimer.data.ApiProvider
import com.fizaan.kimaitimer.data.Customer
import com.fizaan.kimaitimer.data.Prefs
import com.fizaan.kimaitimer.data.Project
import com.fizaan.kimaitimer.data.TimesheetActive
import com.fizaan.kimaitimer.data.TimesheetCreate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Whole-app UI state. */
data class UiState(
    val configured: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,          // an action (start/stop/create) is in flight
    val error: String? = null,
    val running: TimesheetActive? = null,
    val activities: List<Activity> = emptyList(),
    val recent: List<TimesheetActive> = emptyList(),
    val projectName: String = "",
    val showPickDialog: Boolean = false,
    val showCreateDialog: Boolean = false,
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
    private val beginFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _setup = MutableStateFlow(SetupState(baseUrl = defaultUrlHint()))
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

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
                _ui.value = _ui.value.copy(
                    loading = false, running = active, activities = acts, recent = recent,
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
        start(activityId, description = null, tags = null)
    }

    /** Resume a recent activity, carrying over its description and tags. */
    fun resume(item: TimesheetActive) {
        val activityId = item.activity?.id ?: return
        start(activityId, description = item.description, tags = item.tags?.joinToString(","))
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
