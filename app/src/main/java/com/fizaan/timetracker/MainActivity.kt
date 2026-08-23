package com.fizaan.timetracker

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fizaan.timetracker.pomodoro.EXTRA_POMODORO_PHASE
import com.fizaan.timetracker.pomodoro.Phase
import com.fizaan.timetracker.pomodoro.PomodoroAlert
import com.fizaan.timetracker.ui.CalendarScreen
import com.fizaan.timetracker.ui.HeatActions
import com.fizaan.timetracker.ui.TrendActions
import com.fizaan.timetracker.ui.TimeTrackerTheme
import com.fizaan.timetracker.ui.MainScreen
import com.fizaan.timetracker.ui.PomodoroScreen
import com.fizaan.timetracker.ui.SetupScreen
import com.fizaan.timetracker.ui.SheetScreen
import com.fizaan.timetracker.ui.ToolsScreen
import com.fizaan.timetracker.ui.VizScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class Dest(val screen: AppScreen, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    /** The same instance the composables get; used outside composition below. */
    private val vm: MainViewModel by viewModels()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Switching the launcher's `activity-alias` tears down the task it was
     * started from, so a colour change made on screen looks exactly like the app
     * crashing. The icon is brought into line once the app is off screen, where
     * the same work costs nothing to watch.
     */
    override fun onStop() {
        super.onStop()
        vm.syncLauncherIcon()
    }

    /** Set when the app was opened by a pomodoro boundary alert. */
    private val alertPhase = MutableStateFlow<String?>(null)

    /** The running-timer notification is the lock-screen view; ask once for it. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Putting the app on screen by itself when a period ends needs the
     * full-screen-intent permission, which Android 14 stopped granting on
     * install. Without it the alert degrades to a heads-up notification, so
     * this is offered once rather than insisted upon.
     */
    private var askedFullScreen = false

    fun ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || askedFullScreen) return
        askedFullScreen = true
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    /** An alert launch shows over the lock screen and lights the display. */
    private fun applyAlertIntent(intent: Intent?) {
        val phase = intent?.getStringExtra(EXTRA_POMODORO_PHASE) ?: return
        alertPhase.value = phase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAlertIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        PomodoroAlert.ensureChannel(this)
        applyAlertIntent(intent)
        setContent {
            val ui by vm.ui.collectAsState()
            // The accent is read before anything is drawn, so a repaint is a
            // recomposition rather than a restart.
            TimeTrackerTheme(accent = Color(ui.accent)) {
                val setup by vm.setup.collectAsState()
                val viz by vm.viz.collectAsState()
                val sheet by vm.sheet.collectAsState()
                val calendar by vm.calendar.collectAsState()
                val tools by vm.tools.collectAsState()
                val pomodoro by vm.pomodoro.collectAsState()

                // Opened by a boundary alert: land on the pomodoro with the
                // full-screen announcement showing.
                val pending by alertPhase.collectAsState()
                LaunchedEffect(pending) {
                    val name = pending ?: return@LaunchedEffect
                    alertPhase.value = null
                    runCatching { Phase.valueOf(name) }.getOrNull()?.let {
                        vm.navigate(AppScreen.POMODORO)
                        vm.onPomodoroPhaseStarted(it)
                    }
                }

                if (!ui.configured) {
                    SetupScreen(
                        state = setup,
                        onUrl = vm::onUrl,
                        onToken = vm::onToken,
                        onUseLegacy = vm::onUseLegacy,
                        onLegacyUser = vm::onLegacyUser,
                        onTest = vm::testConnection,
                        onSelectCustomer = vm::onSelectCustomer,
                        onSelectProject = vm::onSelectProject,
                        onFinish = vm::finishSetup,
                        onServerless = vm::onServerless,
                        onFinishLocal = vm::finishLocalSetup,
                        accent = ui.accent,
                        onUseAccent = vm::useAccent,
                        onLeave = vm::leaveSetup,
                    )
                } else {
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
                    val dests = listOf(
                        Dest(AppScreen.TIMER, "Timer", Icons.Filled.Timer),
                        Dest(AppScreen.POMODORO, "Pomodoro", Icons.Filled.HourglassBottom),
                        Dest(AppScreen.VIZ, "Visualisations", Icons.Filled.PieChart),
                        Dest(AppScreen.SHEET, "Timesheet", Icons.AutoMirrored.Filled.List),
                        Dest(AppScreen.CALENDAR, "Calendar", Icons.Filled.CalendarMonth),
                        Dest(AppScreen.TOOLS, "Tools", Icons.Filled.Build),
                    )
                    // A running pomodoro hides the drawer button; it must not be
                    // swipeable back in either.
                    val locked = ui.screen == AppScreen.POMODORO && pomodoro.running
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = !locked,
                        drawerContent = {
                            ModalDrawerSheet {
                                Text(
                                    "Time Tracker",
                                    modifier = Modifier.padding(16.dp),
                                )
                                dests.forEach { d ->
                                    NavigationDrawerItem(
                                        label = { Text(d.label) },
                                        icon = { Icon(d.icon, null) },
                                        selected = ui.screen == d.screen,
                                        onClick = {
                                            scope.launch { drawerState.close() }
                                            if (d.screen == AppScreen.POMODORO) {
                                                ensureFullScreenIntentPermission()
                                            }
                                            vm.navigate(d.screen)
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            }
                        },
                    ) {
                        when (ui.screen) {
                            AppScreen.TIMER -> MainScreen(
                                state = ui,
                                onMenu = openDrawer,
                                onStartTap = vm::openPicker,
                                onStopTap = vm::stop,
                                onPickActivity = vm::startActivity,
                                onDismissPicker = vm::dismissPicker,
                                onOpenCreate = vm::openCreate,
                                onDismissCreate = vm::dismissCreate,
                                onCreateActivity = vm::createActivity,
                                onEditTag = vm::editTag,
                                onConfirmTag = vm::confirmTag,
                                onDismissTagDialog = vm::dismissTagDialog,
                                onStopEntry = vm::stopEntry,
                                onDismissStopChoice = vm::dismissStopChoice,
                                onRefresh = vm::refresh,
                                onReconfigure = vm::reconfigure,
                                onClearError = vm::clearError,
                            )
                            AppScreen.POMODORO -> PomodoroScreen(
                                state = pomodoro,
                                onMenu = openDrawer,
                                onOpenSettings = vm::openPomodoroSettings,
                                onSaveSettings = vm::savePomodoroSettings,
                                onDismissSettings = vm::dismissPomodoroSettings,
                                onStartTap = vm::openPomodoroPicker,
                                onPick = vm::startPomodoro,
                                onDismissPicker = vm::dismissPomodoroPicker,
                                onStop = vm::stopPomodoro,
                                onSkip = vm::skipPomodoroPhase,
                                onPhaseStarted = vm::onPomodoroPhaseStarted,
                                onDismissAlert = vm::dismissPomodoroAlert,
                                onClearError = vm::clearPomodoroError,
                            )
                            AppScreen.VIZ -> VizScreen(
                                state = viz,
                                onMenu = openDrawer,
                                onTab = vm::setVizTab,
                                onPieMode = vm::setPieMode,
                                onPeriod = vm::setPeriod,
                                onShiftPie = vm::shiftPie,
                                onPieToday = vm::pieToday,
                                onRefresh = vm::loadViz,
                                onClearError = vm::clearVizError,
                                onLegendClick = vm::openSheetFiltered,
                            )
                            AppScreen.SHEET -> SheetScreen(
                                state = sheet,
                                onMenu = openDrawer,
                                onRefresh = vm::loadSheet,
                                onOpenEdit = vm::openEdit,
                                onDismissEdit = vm::dismissEdit,
                                onSave = vm::saveEdit,
                                onAskDelete = vm::askDelete,
                                onDismissDelete = vm::dismissDelete,
                                onDelete = vm::deleteEntry,
                                onClearError = vm::clearSheetError,
                                onSetActivityFilter = vm::setSheetActivityFilter,
                                onSetTagFilter = vm::setSheetTagFilter,
                                onSetPeriod = vm::setSheetPeriod,
                                onSetDate = vm::setSheetDate,
                                onClearFilters = vm::clearSheetFilters,
                            )
                            AppScreen.CALENDAR -> CalendarScreen(
                                state = calendar,
                                onMenu = openDrawer,
                                onRefresh = vm::loadCalendar,
                                onShift = vm::shiftCalendar,
                                onToday = vm::calendarToday,
                                onClearError = vm::clearCalendarError,
                            )
                            AppScreen.TOOLS -> ToolsScreen(
                                state = tools,
                                onMenu = openDrawer,
                                trend = TrendActions(
                                    setActivity = vm::setTrendActivity,
                                    setMetric = vm::setTrendMetric,
                                    setWindow = vm::setTrendWindow,
                                    setFrom = vm::setTrendFrom,
                                    setTo = vm::setTrendTo,
                                    setColor = vm::setTrendColor,
                                    plot = vm::runTrend,
                                    export = vm::exportTrend,
                                ),
                                heat = HeatActions(
                                    setActivity = vm::setHeatActivity,
                                    shiftMonth = vm::shiftHeatMonth,
                                    setColor = vm::setHeatColor,
                                    export = vm::exportHeat,
                                ),
                                onSetFreqActivity = vm::setFreqActivity,
                                onSetFreqFrom = vm::setFreqFrom,
                                onSetFreqTo = vm::setFreqTo,
                                onCompute = vm::computeFrequency,
                                onSetExportFrom = vm::setExportFrom,
                                onSetExportTo = vm::setExportTo,
                                onSetExportFormat = vm::setExportFormat,
                                onExport = vm::runExport,
                                onPrintHandled = vm::exportPrintHandled,
                                onImport = vm::runImport,
                                onClearImport = vm::clearImport,
                                onSetBatchActivity = vm::setBatchActivity,
                                onSetBatchTag = vm::setBatchTag,
                                onSetBatchMin = vm::setBatchMin,
                                onSetBatchMax = vm::setBatchMax,
                                onSetBatchFrom = vm::setBatchFrom,
                                onSetBatchTo = vm::setBatchTo,
                                onBatchSearch = vm::runBatchSearch,
                                onAskBatch = vm::askBatch,
                                onAskBatchActivityName = vm::askBatchActivityName,
                                onConfirmBatch = vm::confirmBatch,
                                onDismissBatch = vm::dismissBatch,
                                onClearError = vm::clearToolsError,
                            )
                        }
                    }
                }
            }
        }
    }
}
