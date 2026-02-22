package ru.ravel.ultunnel.compose.screen.log

import kotlinx.coroutines.flow.StateFlow
import ru.ravel.ultunnel.constant.Status

interface LogViewerViewModel {
    val uiState: StateFlow<LogUiState>
    val scrollToBottomTrigger: StateFlow<Int>
    val isAtBottom: StateFlow<Boolean>

    fun updateServiceStatus(status: Status)
    fun togglePause()
    fun toggleSearch()
    fun toggleOptionsMenu()
    fun updateSearchQuery(query: String)
    fun setLogLevel(level: LogLevel)
    fun setAutoScrollEnabled(enabled: Boolean)
    fun scrollToBottom()
    fun toggleSelectionMode()
    fun toggleLogSelection(index: Int)
    fun clearSelection()
    fun getSelectedLogsText(): String
    fun getAllLogsText(): String
    fun requestClearLogs()
}
