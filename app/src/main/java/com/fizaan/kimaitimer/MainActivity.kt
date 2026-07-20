package com.fizaan.kimaitimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fizaan.kimaitimer.ui.KimaiTimerTheme
import com.fizaan.kimaitimer.ui.MainScreen
import com.fizaan.kimaitimer.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KimaiTimerTheme {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                val setup by vm.setup.collectAsState()

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
                    MainScreen(
                        state = ui,
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
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
