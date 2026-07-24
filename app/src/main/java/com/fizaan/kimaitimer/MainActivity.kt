package com.fizaan.kimaitimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fizaan.kimaitimer.ui.KimaiTimerTheme
import com.fizaan.kimaitimer.ui.MainScreen
import com.fizaan.kimaitimer.ui.SetupScreen
import com.fizaan.kimaitimer.ui.SheetScreen
import com.fizaan.kimaitimer.ui.VizScreen
import kotlinx.coroutines.launch

private data class Dest(val screen: AppScreen, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KimaiTimerTheme {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                val setup by vm.setup.collectAsState()
                val viz by vm.viz.collectAsState()
                val sheet by vm.sheet.collectAsState()

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
                    )
                } else {
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
                    val dests = listOf(
                        Dest(AppScreen.TIMER, "Timer", Icons.Filled.Timer),
                        Dest(AppScreen.VIZ, "Visualisations", Icons.Filled.PieChart),
                        Dest(AppScreen.SHEET, "Timesheet", Icons.AutoMirrored.Filled.List),
                    )
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                Text(
                                    "Kimai Timer",
                                    modifier = Modifier.padding(16.dp),
                                )
                                dests.forEach { d ->
                                    NavigationDrawerItem(
                                        label = { Text(d.label) },
                                        icon = { Icon(d.icon, null) },
                                        selected = ui.screen == d.screen,
                                        onClick = {
                                            scope.launch { drawerState.close() }
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
                                onResume = vm::resume,
                                onDismissPicker = vm::dismissPicker,
                                onOpenCreate = vm::openCreate,
                                onDismissCreate = vm::dismissCreate,
                                onCreateActivity = vm::createActivity,
                                onEditTag = vm::editTag,
                                onConfirmTag = vm::confirmTag,
                                onDismissTagDialog = vm::dismissTagDialog,
                                onRefresh = vm::refresh,
                                onReconfigure = vm::reconfigure,
                                onClearError = vm::clearError,
                            )
                            AppScreen.VIZ -> VizScreen(
                                state = viz,
                                onMenu = openDrawer,
                                onTab = vm::setVizTab,
                                onPieMode = vm::setPieMode,
                                onPeriod = vm::setPeriod,
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
                                onClearError = vm::clearSheetError,
                                onSetActivityFilter = vm::setSheetActivityFilter,
                                onSetTagFilter = vm::setSheetTagFilter,
                                onSetPeriod = vm::setSheetPeriod,
                                onSetDate = vm::setSheetDate,
                                onClearFilters = vm::clearSheetFilters,
                            )
                        }
                    }
                }
            }
        }
    }
}
