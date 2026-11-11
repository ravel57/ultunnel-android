package ru.ravel.ultunnel.compose.screen.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import ru.ravel.ultunnel.bg.BoxService
import ru.ravel.ultunnel.compose.base.BaseViewModel
import ru.ravel.ultunnel.compose.base.UiEvent
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.database.Profile
import ru.ravel.ultunnel.database.ProfileManager
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.database.TypedProfile
import ru.ravel.ultunnel.utils.AppLifecycleObserver
import ru.ravel.ultunnel.utils.CommandClient
import ru.ravel.ultunnel.utils.CommandTarget
import ru.ravel.ultunnel.utils.HTTPClient
import ru.ravel.ultunnel.utils.RemoteControlManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.ravel.ultunnel.utils.ProfileCheckConfigBuilder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.util.Collections
import java.util.Date

/** Карточки, убранные с главного экрана ULtunnel (и из настроек дашборда). */
val hiddenDashboardCards: Set<CardGroup> = setOf(CardGroup.Debug, CardGroup.Connections)

enum class CardGroup(val pairGroup: CardPairGroup? = null) {
	ClashMode,
	UploadTraffic(CardPairGroup.Traffic),
	DownloadTraffic(CardPairGroup.Traffic),
	Debug(CardPairGroup.Statistics),
	Connections(CardPairGroup.Statistics),
	SystemProxy,
	Profiles,
}

enum class CardPairGroup {
	Traffic,
	Statistics,
}

enum class ProfileCheckState {
	Checking,
	Success,
	Failed,
}

data class ProfileCheckResult(
	val state: ProfileCheckState,
	val message: String,
)

data class DashboardUiState(
	val serviceStatus: Status = Status.Stopped,
	val profiles: List<Profile> = emptyList(),
	val selectedProfileId: Long = -1L,
	val selectedProfileName: String? = null,
	val isLoading: Boolean = false,
	val hasGroups: Boolean = false,
	val groupsCount: Int = 0,
	val connectionsCount: Int = 0,
	val serviceStartTime: Long? = null,
	val deprecatedNotes: List<DeprecatedNote> = emptyList(),
	val showDeprecatedDialog: Boolean = false,
	val showAddProfileSheet: Boolean = false,
	val showProfilePickerSheet: Boolean = false,
	val updatingProfileId: Long? = null,
	val updatedProfileId: Long? = null,
	val checkingProfiles: Boolean = false,
	val profileCheckResults: Map<Long, ProfileCheckResult> = emptyMap(),
	// Status
	val memory: String = "",
	val goroutines: String = "",
	val isStatusVisible: Boolean = false,
	// Traffic
	val trafficVisible: Boolean = false,
	val connectionsIn: String = "0",
	val connectionsOut: String = "0",
	val uplink: String = "0 B/s",
	val downlink: String = "0 B/s",
	val uplinkTotal: String = "0 B",
	val downlinkTotal: String = "0 B",
	val uplinkHistory: List<Float> = List(30) { 0f },
	val downlinkHistory: List<Float> = List(30) { 0f },
	// Clash Mode
	val clashModeVisible: Boolean = false,
	val clashModes: List<String> = emptyList(),
	val selectedClashMode: String = "",
	// System Proxy
	val systemProxyVisible: Boolean = false,
	val systemProxyEnabled: Boolean = false,
	val systemProxySwitching: Boolean = false,
	// Card visibility settings
	val visibleCards: Set<CardGroup> =
		setOf(
			CardGroup.ClashMode,
			CardGroup.UploadTraffic,
			CardGroup.DownloadTraffic,
			CardGroup.Debug,
			CardGroup.Connections,
			CardGroup.SystemProxy,
			CardGroup.Profiles,
		),
	val cardOrder: List<CardGroup> =
		listOf(
			CardGroup.UploadTraffic,
			CardGroup.DownloadTraffic,
			CardGroup.Debug,
			CardGroup.Connections,
			CardGroup.SystemProxy,
			CardGroup.ClashMode,
			CardGroup.Profiles,
		),
	val showCardSettingsDialog: Boolean = false,
) {
	data class DeprecatedNote(val message: String, val migrationLink: String?)
}

// DashboardViewModel now only uses UiEvent for all events
// No need for DashboardEvent anymore as all events are handled globally

class DashboardViewModel :
	BaseViewModel<DashboardUiState, UiEvent>(),
	CommandClient.Handler {
	private val _serviceStatus = MutableStateFlow(Status.Stopped)
	val serviceStatus: StateFlow<Status> = _serviceStatus.asStateFlow()

	internal val commandClient =
		CommandClient(
			viewModelScope,
			listOf(
				CommandClient.ConnectionType.Status,
				CommandClient.ConnectionType.ClashMode,
				CommandClient.ConnectionType.Groups,
			),
			this,
		)

	override fun createInitialState(): DashboardUiState {
		val savedOrder = loadItemOrder()
		val disabledItems = loadDisabledItems()

		// Calculate visible items (all items minus disabled)
		val allItems = CardGroup.values().toSet()
		val visibleCards = allItems - disabledItems

		return DashboardUiState(
			cardOrder = savedOrder,
			visibleCards = visibleCards,
		)
	}

	init {
		loadProfiles()
		ProfileManager.registerCallback(::onProfilesChanged)

		viewModelScope.launch {
			combine(
				AppLifecycleObserver.isForeground,
				RemoteControlManager.remoteServer,
				RemoteControlManager.isConnected,
				_serviceStatus,
			) { foreground, remoteServer, remoteConnected, status ->
				SessionTarget(
					connect = foreground &&
						if (remoteServer != null) remoteConnected else status == Status.Started,
					remoteServerId = remoteServer?.id,
				)
			}.distinctUntilChanged().collect { target ->
				if (target.connect) {
					commandClient.connect()
				} else {
					commandClient.disconnect()
				}
			}
		}
	}

	private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

	override fun onCleared() {
		super.onCleared()
		ProfileManager.unregisterCallback(::onProfilesChanged)
		commandClient.disconnect()
	}

	private fun onProfilesChanged() {
		loadProfiles()
	}

	private fun loadProfiles() {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val profiles = ProfileManager.list()
				val selectedId = Settings.selectedProfile

				withContext(Dispatchers.Main) {
					updateState {
						copy(
							profiles = profiles,
							selectedProfileId = selectedId,
							selectedProfileName = profiles.find { it.id == selectedId }?.name,
						)
					}
				}
			} catch (e: Exception) {
				sendError(e)
			}
		}
	}

	private fun checkDeprecatedNotes() {
		viewModelScope.launch(Dispatchers.IO) {
			runCatching {
				// Check if deprecated warnings are disabled
				if (Settings.disableDeprecatedWarnings) {
					return@launch
				}

				val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
				if (notes.hasNext()) {
					val notesList = mutableListOf<DashboardUiState.DeprecatedNote>()
					while (notes.hasNext()) {
						val note = notes.next()
						notesList.add(
							DashboardUiState.DeprecatedNote(
								message = note.message(),
								migrationLink = note.migrationLink,
							),
						)
					}
					withContext(Dispatchers.Main) {
						updateState {
							copy(
								deprecatedNotes = notesList,
								showDeprecatedDialog = notesList.isNotEmpty(),
							)
						}
					}
				}
			}
		}
	}

	fun toggleService() {
		when (currentState.serviceStatus) {
			Status.Starting, Status.Started -> stopService()
			Status.Stopped -> sendGlobalEvent(UiEvent.RequestStartService)
			else -> { /* Ignore while transitioning */
			}
		}
	}

	private fun stopService() {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				BoxService.stop()
				// Status will be updated via updateServiceStatus callback
			} catch (e: Exception) {
				sendError(e)
			}
		}
	}

	fun dismissDeprecatedNote() {
		val notes = currentState.deprecatedNotes
		if (notes.isNotEmpty()) {
			updateState {
				copy(
					deprecatedNotes = notes.drop(1),
					showDeprecatedDialog = notes.size > 1,
				)
			}
		}
	}

	fun selectProfile(profileId: Long) {
		if (currentState.isLoading) return

		viewModelScope.launch(Dispatchers.IO) {
			try {
				updateState { copy(isLoading = true) }
				val profile = ProfileManager.get(profileId) ?: return@launch

				Settings.selectedProfile = profileId
				applySelectedProfile()

				withContext(Dispatchers.Main) {
					loadProfiles()
				}
			} catch (e: Exception) {
				sendError(e)
			} finally {
				updateState { copy(isLoading = false) }
			}
		}
	}

	private suspend fun applySelectedProfile() {
		if (_serviceStatus.value != Status.Started) return
		if (Settings.detectServiceMode() == Settings.serviceMode) {
			Libbox.newStandaloneCommandClient().serviceReload()
			return
		}
		BoxService.stop()
		for (i in 0 until 30) {
			if (_serviceStatus.value == Status.Stopped) {
				break
			}
			delay(100L)
		}
		sendGlobalEvent(UiEvent.RequestStartService)
	}

	fun editProfile(profile: Profile) {
		updateState { copy(showProfilePickerSheet = false) }
		sendGlobalEvent(UiEvent.EditProfile(profile.id))
	}

	fun deleteProfile(profile: Profile) {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				// Update UI immediately for responsiveness
				withContext(Dispatchers.Main) {
					updateState {
						copy(
							profiles = profiles.filter { p -> p.id != profile.id },
						)
					}
				}
				// Then delete from database
				ProfileManager.delete(profile)
			} catch (e: Exception) {
				// Reload profiles if deletion fails
				loadProfiles()
				sendError(e)
			}
		}
	}

	fun shareProfile(profile: Profile) {
		// Handled directly in ProfilesCard
	}

	fun shareProfileURL(profile: Profile) {
		// Handled directly in ProfilesCard
	}

	fun updateProfile(profile: Profile) {
		if (profile.typed.type != TypedProfile.Type.Remote) return

		viewModelScope.launch(Dispatchers.IO) {
			// Set updating state
			withContext(Dispatchers.Main) {
				updateState { copy(updatingProfileId = profile.id) }
			}

			try {
				// Fetch remote config
				val content = HTTPClient().use { it.getString(profile.typed.remoteURL) }
				Libbox.checkConfig(content)

				// Check if content changed
				val file = File(profile.typed.path)
				var contentChanged = false
				if (!file.exists() || file.readText() != content) {
					file.writeText(content)
					contentChanged = true
				}

				// Update last updated time
				profile.typed.lastUpdated = Date()
				ProfileManager.update(profile)

				if (contentChanged && profile.id == Settings.selectedProfile) {
					try {
						applySelectedProfile()
					} catch (e: Exception) {
						sendError(e)
					}
				}

				// Reload profiles
				loadProfiles()

				// Show success state
				withContext(Dispatchers.Main) {
					updateState { copy(updatingProfileId = null, updatedProfileId = profile.id) }
				}

				// Clear success state after delay
				withContext(Dispatchers.Main) {
					delay(1500)
					updateState { copy(updatedProfileId = null) }
				}
			} catch (e: Exception) {
				sendErrorMessage("Failed to update profile: ${e.message}")
				// Clear updating state on error
				withContext(Dispatchers.Main) {
					updateState { copy(updatingProfileId = null) }
				}
			}
		}
	}

	fun moveProfile(from: Int, to: Int) {
		val currentProfiles = currentState.profiles.toMutableList()

		if (from < to) {
			for (i in from until to) {
				Collections.swap(currentProfiles, i, i + 1)
			}
		} else {
			for (i in from downTo to + 1) {
				Collections.swap(currentProfiles, i, i - 1)
			}
		}

		// Update UI immediately
		updateState { copy(profiles = currentProfiles) }

		// Update user order in database
		viewModelScope.launch(Dispatchers.IO) {
			currentProfiles.forEachIndexed { index, profile ->
				profile.userOrder = index.toLong()
			}
			ProfileManager.update(currentProfiles)
		}
	}

	fun showAddProfileSheet() {
		updateState { copy(showAddProfileSheet = true) }
	}

	fun hideAddProfileSheet() {
		updateState { copy(showAddProfileSheet = false) }
	}

	fun showProfilePickerSheet() {
		updateState { copy(showProfilePickerSheet = true) }
	}

	fun hideProfilePickerSheet() {
		updateState { copy(showProfilePickerSheet = false) }
	}

	fun updateServiceStatus(status: Status) {
		viewModelScope.launch {
			_serviceStatus.emit(status)
			updateState {
				copy(
					serviceStatus = status,
					isStatusVisible =
					if (RemoteControlManager.remoteServer.value != null) {
						isStatusVisible
					} else {
						status == Status.Starting || status == Status.Started
					},
				)
			}
			handleServiceStatusChange(status)
		}
	}

	private fun handleServiceStatusChange(status: Status) {
		val isRemote = RemoteControlManager.remoteServer.value != null
		when (status) {
			Status.Started -> {
				checkDeprecatedNotes()
				if (isRemote) {
					return
				}
				reloadSystemProxyStatus()
				reloadStartedAt()
			}

			Status.Stopped -> {
				if (isRemote) {
					return
				}
				updateState {
					copy(
						hasGroups = false,
						groupsCount = 0,
						connectionsCount = 0,
						serviceStartTime = null,
						clashModeVisible = false,
						systemProxyVisible = false,
						trafficVisible = false,
						memory = "",
						goroutines = "",
						connectionsIn = "0",
						connectionsOut = "0",
						uplink = "0 B/s",
						downlink = "0 B/s",
						uplinkTotal = "0 B",
						downlinkTotal = "0 B",
						uplinkHistory = List(30) { 0f },
						downlinkHistory = List(30) { 0f },
					)
				}
			}

			else -> {}
		}
	}

	private fun reloadStartedAt() {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val startedAt = Libbox.newStandaloneCommandClient().startedAt
				withContext(Dispatchers.Main) {
					updateState {
						copy(serviceStartTime = startedAt)
					}
				}
			} catch (_: Exception) {
			}
		}
	}

	private fun reloadSystemProxyStatus() {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val status = Libbox.newStandaloneCommandClient().systemProxyStatus
				withContext(Dispatchers.Main) {
					updateState {
						copy(
							systemProxyVisible = status.available,
							systemProxyEnabled = status.enabled,
						)
					}
				}
			} catch (e: Exception) {
				// Ignore errors
			}
		}
	}

	fun toggleSystemProxy(enabled: Boolean) {
		if (currentState.systemProxySwitching) return

		viewModelScope.launch(Dispatchers.IO) {
			try {
				updateState { copy(systemProxySwitching = true) }
				Settings.systemProxyEnabled = enabled
				Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
				delay(1000L)
				withContext(Dispatchers.Main) {
					updateState {
						copy(
							systemProxyEnabled = enabled,
							systemProxySwitching = false,
						)
					}
				}
			} catch (e: Exception) {
				sendError(e)
				updateState { copy(systemProxySwitching = false) }
			}
		}
	}

	fun selectClashMode(mode: String) {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				CommandTarget.standaloneClient().setClashMode(mode)
				// Update UI state directly without reconnecting
				withContext(Dispatchers.Main) {
					updateState {
						copy(selectedClashMode = mode)
					}
				}
			} catch (e: Exception) {
				sendError(e)
			}
		}
	}

	// CommandClient.Handler implementation
	override fun onConnected() {
		viewModelScope.launch(Dispatchers.Main) {
			updateState { copy(isStatusVisible = true) }
			// Returning from remote control skipped the local reloads that
			// normally run when the service starts.
			if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Started) {
				reloadSystemProxyStatus()
				reloadStartedAt()
			}
		}
	}

	override fun onDisconnected() {
		viewModelScope.launch(Dispatchers.Main) {
			updateState {
				copy(
					memory = "",
					goroutines = "",
					isStatusVisible = false,
				)
			}
		}
	}

	override fun updateStatus(status: StatusMessage) {
		viewModelScope.launch(Dispatchers.Main) {
			updateState {
				// Update history by adding new values and removing old ones
				val newUplinkHistory = (uplinkHistory.drop(1) + status.uplink.toFloat())
				val newDownlinkHistory = (downlinkHistory.drop(1) + status.downlink.toFloat())

				// Format the total values
				val newUplinkTotal = Libbox.formatBytes(status.uplinkTotal)
				val newDownlinkTotal = Libbox.formatBytes(status.downlinkTotal)

				copy(
					memory = Libbox.formatBytes(status.memory),
					goroutines = status.goroutines.toString(),
					// Only set trafficVisible to true, never back to false from status updates
					trafficVisible = if (status.trafficAvailable) true else trafficVisible,
					connectionsCount = status.connectionsIn,
					connectionsIn = status.connectionsIn.toString(),
					connectionsOut = status.connectionsOut.toString(),
					uplink = "${Libbox.formatBytes(status.uplink)}/s",
					downlink = "${Libbox.formatBytes(status.downlink)}/s",
					// Only update total values if they've actually changed
					uplinkTotal = if (newUplinkTotal != uplinkTotal) newUplinkTotal else uplinkTotal,
					downlinkTotal = if (newDownlinkTotal != downlinkTotal) newDownlinkTotal else downlinkTotal,
					uplinkHistory = newUplinkHistory,
					downlinkHistory = newDownlinkHistory,
				)
			}
		}
	}

	override fun initializeClashMode(modeList: List<String>, currentMode: String) {
		viewModelScope.launch(Dispatchers.Main) {
			updateState {
				copy(
					clashModeVisible = modeList.size > 1,
					clashModes = modeList,
					selectedClashMode = currentMode,
				)
			}
		}
	}

	override fun updateClashMode(newMode: String) {
		viewModelScope.launch(Dispatchers.Main) {
			updateState {
				copy(selectedClashMode = newMode)
			}
		}
	}

	override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
		viewModelScope.launch(Dispatchers.Main) {
			val hasGroups = newGroups.isNotEmpty()
			updateState {
				copy(hasGroups = hasGroups, groupsCount = newGroups.size)
			}
		}
	}

	fun toggleCardSettingsDialog() {
		updateState {
			copy(showCardSettingsDialog = !showCardSettingsDialog)
		}
	}

	fun toggleCardVisibility(cardGroup: CardGroup) {
		// Profiles card cannot be disabled
		if (cardGroup == CardGroup.Profiles) {
			return
		}

		updateState {
			val newVisibleCards =
				if (visibleCards.contains(cardGroup)) {
					visibleCards - cardGroup
				} else {
					visibleCards + cardGroup
				}
			// Save disabled items to settings
			saveDisabledItems(newVisibleCards)
			// Also save the current order if not already saved (indicates user has configured dashboard)
			if (Settings.dashboardItemOrder.isBlank()) {
				saveItemOrder(cardOrder)
			}
			copy(visibleCards = newVisibleCards)
		}
	}

	fun closeCardSettingsDialog() {
		updateState {
			copy(showCardSettingsDialog = false)
		}
	}

	fun reorderCards(newOrder: List<CardGroup>) {
		updateState {
			saveItemOrder(newOrder)
			copy(cardOrder = newOrder)
		}
	}

	fun resetCardOrder() {
		// Clear saved settings to restore defaults
		Settings.dashboardItemOrder = ""
		Settings.dashboardDisabledItems = emptySet()

		updateState {
			copy(
				cardOrder = getDefaultItemOrder(),
				visibleCards = CardGroup.values().toSet(),
			)
		}
	}

	// Helper functions for serialization
	private fun getDefaultItemOrder() = listOf(
		CardGroup.UploadTraffic,
		CardGroup.DownloadTraffic,
		CardGroup.Debug,
		CardGroup.Connections,
		CardGroup.SystemProxy,
		CardGroup.ClashMode,
		CardGroup.Profiles,
	)

	private fun loadItemOrder(): List<CardGroup> {
		val savedOrder = Settings.dashboardItemOrder
		if (savedOrder.isBlank()) {
			return getDefaultItemOrder()
		}

		return try {
			val jsonArray = JSONArray(savedOrder)
			val order = mutableListOf<CardGroup>()

			for (i in 0 until jsonArray.length()) {
				val itemName = jsonArray.getString(i)
				stringToCardGroup(itemName)?.let { order.add(it) }
			}

			// Add any new items that aren't in the saved order
			val allItems = CardGroup.values().toSet()
			val savedItems = order.toSet()
			val newItems = allItems - savedItems

			order.addAll(newItems)
			order
		} catch (e: JSONException) {
			getDefaultItemOrder()
		}
	}

	private fun saveItemOrder(order: List<CardGroup>) {
		val jsonArray = JSONArray()
		order.forEach { item ->
			jsonArray.put(cardGroupToString(item))
		}
		Settings.dashboardItemOrder = jsonArray.toString()
	}

	private fun loadDisabledItems(): Set<CardGroup> {
		val savedDisabled = Settings.dashboardDisabledItems
		// Filter out Profiles from disabled items (it cannot be disabled)
		return savedDisabled.mapNotNull { stringToCardGroup(it) }
			.filter { it != CardGroup.Profiles }
			.toSet()
	}

	private fun saveDisabledItems(visibleCards: Set<CardGroup>) {
		val allItems = CardGroup.values().toSet()
		// Always ensure Profiles is in visibleCards (cannot be disabled)
		val actualVisibleCards = visibleCards + CardGroup.Profiles
		val disabledItems = allItems - actualVisibleCards
		Settings.dashboardDisabledItems = disabledItems.map { cardGroupToString(it) }.toSet()
	}

	private fun cardGroupToString(card: CardGroup): String = card.name

	private fun stringToCardGroup(name: String): CardGroup? = try {
		CardGroup.valueOf(name)
	} catch (e: IllegalArgumentException) {
		null
	}


	fun checkAllProfilesThroughVpn() {
		if (currentState.checkingProfiles) return

		viewModelScope.launch(Dispatchers.IO) {
			val originalServiceStatus = _serviceStatus.value
			val shouldRestoreService = originalServiceStatus == Status.Started || originalServiceStatus == Status.Starting
			var checkConfig: ProfileCheckConfigBuilder.Result? = null

			try {
				val profiles = ProfileManager.list()
				if (profiles.isEmpty()) return@launch

				withContext(Dispatchers.Main) {
					updateState {
						copy(
							checkingProfiles = true,
							profileCheckResults = profiles.associate { profile ->
								profile.id to ProfileCheckResult(
									state = ProfileCheckState.Checking,
									message = "Подготовка конфига...",
								)
							},
						)
					}
				}

				// Один общий конфиг: на каждый профиль свой локальный mixed-инбаунд.
				val builtConfig = ProfileCheckConfigBuilder.build(profiles)
				checkConfig = builtConfig

				withContext(Dispatchers.Main) {
					updateState {
						copy(
							profileCheckResults = profileCheckResults.toMutableMap().apply {
								builtConfig.errors.forEach { (profileId, message) ->
									put(profileId, ProfileCheckResult(ProfileCheckState.Failed, message))
								}
							},
						)
					}
				}

				val content = builtConfig.content ?: return@launch

				stopServiceAndWait()

				BoxService.configCheckContent = content
				BoxService.start()

				try {
					// Готовность определяем по локальным портам, а не по статусу сервиса:
					// экран может быть привязан к другому классу сервиса (VPN/Proxy).
					waitLocalPortsOpen(
						ports = builtConfig.ports.values,
						timeoutMs = CHECK_SERVICE_START_TIMEOUT_MS,
					)
				} catch (e: Exception) {
					val message = e.message ?: "Сервис проверки не запустился"
					withContext(Dispatchers.Main) {
						builtConfig.ports.keys.forEach { profileId ->
							updateProfileCheckResult(profileId, ProfileCheckResult(ProfileCheckState.Failed, message))
						}
					}
					return@launch
				}

				withContext(Dispatchers.Main) {
					builtConfig.ports.keys.forEach { profileId ->
						updateProfileCheckResult(
							profileId,
							ProfileCheckResult(ProfileCheckState.Checking, "Проверка..."),
						)
					}
				}

				// Все профили проверяются одновременно, каждый запрос — не дольше PROFILE_CHECK_TIMEOUT_MS.
				coroutineScope {
					builtConfig.ports.map { (profileId, port) ->
						launch(Dispatchers.IO) {
							val result = try {
								val ip = requestIpThroughProxy(port)
								ProfileCheckResult(ProfileCheckState.Success, "Работает, IP: $ip")
							} catch (e: TimeoutCancellationException) {
								ProfileCheckResult(
									ProfileCheckState.Failed,
									"Нет ответа за ${PROFILE_CHECK_TIMEOUT_MS / 1000.0} с",
								)
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								ProfileCheckResult(
									ProfileCheckState.Failed,
									e.message ?: "Конфиг не работает",
								)
							}

							withContext(Dispatchers.Main) {
								updateProfileCheckResult(profileId, result)
							}
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				sendError(e)
			} finally {
				withContext(NonCancellable) {
					if (BoxService.configCheckMode) {
						runCatching {
							BoxService.stop()
							waitLocalPortClosed(
								port = checkConfig?.ports?.values?.firstOrNull(),
								timeoutMs = CHECK_SERVICE_STOP_TIMEOUT_MS,
							)
						}
						BoxService.configCheckContent = null

						if (shouldRestoreService && Settings.selectedProfile != -1L) {
							BoxService.start()
						}
					}

					withContext(Dispatchers.Main) {
						updateState { copy(checkingProfiles = false) }
					}
				}
			}
		}
	}


	private fun updateProfileCheckResult(profileId: Long, result: ProfileCheckResult) {
		updateState {
			copy(
				profileCheckResults = profileCheckResults.toMutableMap().apply {
					put(profileId, result)
				},
			)
		}
	}

	private suspend fun stopServiceAndWait() {
		if (_serviceStatus.value == Status.Stopped) return

		BoxService.stop()

		waitServiceStatus(
			expected = Status.Stopped,
			timeoutMs = 15_000L,
			timeoutMessage = "VPN не остановился за 15 секунд",
		)
	}

	private suspend fun waitServiceStatus(
		expected: Status,
		timeoutMs: Long,
		timeoutMessage: String,
	) {
		try {
			withTimeout(timeoutMs) {
				while (_serviceStatus.value != expected) {
					delay(250L)
				}
			}
		} catch (e: Exception) {
			throw IllegalStateException("$timeoutMessage. Текущий статус: ${_serviceStatus.value}")
		}
	}

	/**
	 * Запрос к ipinfo.io через локальный mixed-инбаунд профиля.
	 * Общий дедлайн — PROFILE_CHECK_TIMEOUT_MS: по таймауту соединение рвётся принудительно.
	 */
    private suspend fun requestIpThroughProxy(port: Int): String {
        val errors = mutableListOf<String>()
        for (url in listOf("https://ipinfo.io/ip", "https://api.ipify.org")) {
            try { return requestIpFromUrl(port, url) }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                errors.add("$url: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        error(errors.joinToString("; "))
    }

    private suspend fun requestIpFromUrl(port: Int, url: String): String {
		val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
		val connection = URL(url).openConnection(proxy) as HttpURLConnection

		return try {
			coroutineScope {
				val request = async(Dispatchers.IO) {
					connection.requestMethod = "GET"
					connection.connectTimeout = PROFILE_CHECK_TIMEOUT_MS.toInt()
					connection.readTimeout = PROFILE_CHECK_TIMEOUT_MS.toInt()
					connection.useCaches = false
					connection.setRequestProperty("Cache-Control", "no-cache")
					connection.setRequestProperty("User-Agent", "Ultunnel/AndroidConfigCheck")

					val code = connection.responseCode

					if (code !in 200..299) {
						throw IllegalStateException("ipinfo.io/ip вернул HTTP $code")
					}

					val ip = connection.inputStream.bufferedReader().use { it.readText() }.trim()

					if (!com.google.common.net.InetAddresses.isInetAddress(ip)) {
						throw IllegalStateException("ipinfo.io/ip вернул пустой IP")
					}

					ip
				}

				try {
					withTimeout(PROFILE_CHECK_TIMEOUT_MS) { request.await() }
				} finally {
					// Разблокирует поток с запросом, если сработал таймаут.
					connection.disconnect()
				}
			}
		} finally {
			connection.disconnect()
		}
	}

	private suspend fun waitLocalPortsOpen(ports: Collection<Int>, timeoutMs: Long) {
		val firstPort = ports.firstOrNull() ?: return
		try {
			withTimeout(timeoutMs) {
				while (!isLocalPortOpen(firstPort)) {
					delay(100L)
				}
			}
		} catch (e: TimeoutCancellationException) {
			throw IllegalStateException("Сервис проверки не запустился за ${timeoutMs / 1000} с")
		}
	}

	private suspend fun waitLocalPortClosed(port: Int?, timeoutMs: Long) {
		port ?: return
		withTimeoutOrNull(timeoutMs) {
			while (isLocalPortOpen(port)) {
				delay(100L)
			}
		}
	}

	private fun isLocalPortOpen(port: Int): Boolean = try {
		Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
		true
	} catch (_: IOException) {
		false
	}

	private companion object {
		const val PROFILE_CHECK_TIMEOUT_MS = 10_000L
		const val CHECK_SERVICE_START_TIMEOUT_MS = 15_000L
		const val CHECK_SERVICE_STOP_TIMEOUT_MS = 10_000L
	}
}
