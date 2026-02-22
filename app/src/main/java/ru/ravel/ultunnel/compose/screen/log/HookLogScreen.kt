package ru.ravel.ultunnel.compose.screen.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.constant.Status
import androidx.compose.ui.res.stringResource

@Composable
fun HookLogScreen(onBack: () -> Unit) {
    val viewModel: HookLogViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadLogs(context)
    }

    LogScreen(
        serviceStatus = Status.Stopped,
        showStartFab = false,
        showStatusBar = false,
        title = stringResource(R.string.title_log),
        viewModel = viewModel,
        showPause = false,
        showClear = false,
        showStatusInfo = false,
        emptyMessage = stringResource(R.string.privilege_settings_hook_logs_empty),
        saveFilePrefix = "hook_logs",
        onBack = onBack,
    )
}
