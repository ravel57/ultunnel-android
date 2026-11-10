package ru.ravel.ultunnel.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.compose.component.RemoteControlMenuItems
import ru.ravel.ultunnel.compose.component.rememberRemoteServers
import ru.ravel.ultunnel.compose.navigation.NewProfileArgs
import ru.ravel.ultunnel.compose.topbar.LocalScaffoldPadding
import ru.ravel.ultunnel.compose.topbar.OverrideTopBar
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.utils.ProfileConfigsUpdater
import ru.ravel.ultunnel.utils.RemoteControlManager
import kotlinx.coroutines.launch

data class CardRenderItem(val cards: List<CardGroup>, val isRow: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(
	serviceStatus: Status = Status.Stopped,
	showStartFab: Boolean = false,
	showStatusBar: Boolean = false,
	onOpenNewProfile: (NewProfileArgs) -> Unit = {},
	viewModel: DashboardViewModel = viewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val remoteServer by RemoteControlManager.remoteServer.collectAsState()
	val remoteConnected by RemoteControlManager.isConnected.collectAsState()
	val isRemote = remoteServer != null
	val remoteServers by rememberRemoteServers()
	var showOthersMenu by remember { mutableStateOf(false) }

	val sheetState = rememberModalBottomSheetState()
	val scope = rememberCoroutineScope()
	val context = LocalContext.current

	var refreshTick by remember { mutableIntStateOf(0) }
	var isRefreshing by remember { mutableStateOf(false) }

	val pullRefreshState = rememberPullRefreshState(
		refreshing = isRefreshing,
		onRefresh = {
			if (!isRefreshing) {
				isRefreshing = true
				refreshTick++
			}
		},
	)

	LaunchedEffect(refreshTick) {
		if (isRefreshing) {
			try {
				ProfileConfigsUpdater.refreshProfiles(context)
			} catch (e: Exception) {
				Toast.makeText(context, e.message ?: "Ошибка обновления профилей", Toast.LENGTH_LONG).show()
			} finally {
				isRefreshing = false
			}
		}
	}

	OverrideTopBar {
		TopAppBar(
			title = { Text(stringResource(R.string.title_dashboard)) },
			actions = {
				Box {
					IconButton(onClick = { showOthersMenu = true }) {
						Icon(
							imageVector = Icons.Default.MoreVert,
							contentDescription = stringResource(R.string.title_others),
						)
					}
					DropdownMenu(
						expanded = showOthersMenu,
						onDismissRequest = { showOthersMenu = false },
					) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.dashboard_items)) },
							leadingIcon = {
								Icon(
									imageVector = Icons.Default.GridView,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
								)
							},
							onClick = {
								showOthersMenu = false
								viewModel.toggleCardSettingsDialog()
							},
						)
						RemoteControlMenuItems(
							servers = remoteServers,
							onAction = { showOthersMenu = false },
						)
					}
				}
			},
		)
	}

	// Show dashboard settings bottom sheet
	if (uiState.showCardSettingsDialog) {
		DashboardSettingsBottomSheet(
			sheetState = sheetState,
			visibleCards = uiState.visibleCards - hiddenDashboardCards,
			cardOrder = uiState.cardOrder - hiddenDashboardCards,
			onToggleCard = viewModel::toggleCardVisibility,
			onReorderCards = viewModel::reorderCards,
			onResetOrder = viewModel::resetCardOrder,
			onDismiss = {
				scope.launch {
					sheetState.hide()
					viewModel.closeCardSettingsDialog()
				}
			},
		)
	}

	if (isRemote && !remoteConnected) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			CircularProgressIndicator()
		}
		return
	}

	val scaffoldPadding = LocalScaffoldPadding.current

	Box(
		modifier = Modifier
			.fillMaxSize()
			.pullRefresh(pullRefreshState),
	) {
		PullRefreshIndicator(
			refreshing = isRefreshing,
			state = pullRefreshState,
			// Контент рисуется под верхней панелью (edge-to-edge), поэтому опускаем
			// индикатор на высоту хедера, иначе он прячется за ним.
			modifier = Modifier
				.align(Alignment.TopCenter)
				.padding(top = scaffoldPadding.calculateTopPadding())
				.zIndex(10f),
		)

		val bottomPadding = when {
			showStartFab -> 88.dp
			showStatusBar -> 74.dp
			else -> 0.dp
		}
		LazyColumn(
			modifier =
			Modifier
				.fillMaxSize()
				.padding(scaffoldPadding)
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
			contentPadding = PaddingValues(bottom = bottomPadding),
		) {
			// Dynamic dashboard cards
			// Show cards when service is running OR if it's the Profiles card (always available)
			val serviceRunning = uiState.isStatusVisible

			// Filter cards based on availability
			val actuallyVisibleCards =
				uiState.visibleCards.filter { it !in hiddenDashboardCards }.filter { cardGroup ->
					when {
						// The remote dashboard only renders cards backed by the
						// command protocol: profiles and system proxy are
						// operations on the local device.
						isRemote ->
							cardGroup != CardGroup.Profiles &&
								cardGroup != CardGroup.SystemProxy &&
								serviceRunning &&
								isCardAvailableWhenServiceRunning(cardGroup, uiState)

						cardGroup == CardGroup.Profiles -> true // Profiles card is always available
						else -> serviceRunning && isCardAvailableWhenServiceRunning(cardGroup, uiState)
					}
				}.toSet()

			// Process cards to group half-width cards together
			val cardRenderItems =
				processCardsForRendering(
					cardOrder = uiState.cardOrder,
					visibleCards = actuallyVisibleCards,
				)

			items(cardRenderItems) { renderItem ->
				if (renderItem.isRow && renderItem.cards.size >= 2) {
					// Render two half-width cards in a row
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(16.dp),
					) {
						renderItem.cards.forEach { cardGroup ->
							DashboardCardRenderer(
								cardGroup = cardGroup,
								uiState = uiState,
								onClashModeSelected = viewModel::selectClashMode,
								onSystemProxyToggle = viewModel::toggleSystemProxy,
								// Profile card specific props
								profiles = uiState.profiles,
								selectedProfileId = uiState.selectedProfileId,
								isLoading = uiState.isLoading,
								showAddProfileSheet = uiState.showAddProfileSheet,
								showProfilePickerSheet = uiState.showProfilePickerSheet,
								updatingProfileId = uiState.updatingProfileId,
								updatedProfileId = uiState.updatedProfileId,
								onProfileSelected = viewModel::selectProfile,
//								onProfileEdit = viewModel::editProfile,
								onProfileDelete = viewModel::deleteProfile,
								onProfileShare = viewModel::shareProfile,
								onProfileShareURL = viewModel::shareProfileURL,
								onProfileUpdate = viewModel::updateProfile,
								onProfileMove = viewModel::moveProfile,
								onShowAddProfileSheet = viewModel::showAddProfileSheet,
								onHideAddProfileSheet = viewModel::hideAddProfileSheet,
								onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
								onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
								onOpenNewProfile = onOpenNewProfile,
								onCheckProfiles = viewModel::checkAllProfilesThroughVpn,
								commandClient = viewModel.commandClient,
								modifier =
								Modifier
									.weight(1f)
									.fillMaxWidth(),
							)
						}
					}
				} else {
					// Render single card (full-width or single half-width)
					renderItem.cards.forEach { cardGroup ->
						DashboardCardRenderer(
							cardGroup = cardGroup,
							uiState = uiState,
							serviceStatus = serviceStatus,
							onClashModeSelected = viewModel::selectClashMode,
							onSystemProxyToggle = viewModel::toggleSystemProxy,
							// Profile card specific props
							profiles = uiState.profiles,
							selectedProfileId = uiState.selectedProfileId,
							isLoading = uiState.isLoading,
							showAddProfileSheet = uiState.showAddProfileSheet,
							showProfilePickerSheet = uiState.showProfilePickerSheet,
							updatingProfileId = uiState.updatingProfileId,
							updatedProfileId = uiState.updatedProfileId,
							onProfileSelected = viewModel::selectProfile,
//							onProfileEdit = viewModel::editProfile,
							onProfileDelete = viewModel::deleteProfile,
							onProfileShare = viewModel::shareProfile,
							onProfileShareURL = viewModel::shareProfileURL,
							onProfileUpdate = viewModel::updateProfile,
							onProfileMove = viewModel::moveProfile,
							onShowAddProfileSheet = viewModel::showAddProfileSheet,
							onHideAddProfileSheet = viewModel::hideAddProfileSheet,
							onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
							onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
							onOpenNewProfile = onOpenNewProfile,
							onCheckProfiles = viewModel::checkAllProfilesThroughVpn,
							commandClient = viewModel.commandClient,
						)
					}
				}
			}
		}
	}
}

/**
 * Process cards for rendering, grouping consecutive cards of the same pair group into rows
 */
fun processCardsForRendering(
	cardOrder: List<CardGroup>,
	visibleCards: Set<CardGroup>,
): List<CardRenderItem> {
	val renderItems = mutableListOf<CardRenderItem>()
	val visibleOrderedCards = cardOrder.filter { visibleCards.contains(it) }

	var i = 0
	while (i < visibleOrderedCards.size) {
		val currentCard = visibleOrderedCards[i]
		val pairGroup = currentCard.pairGroup
		val nextCard = visibleOrderedCards.getOrNull(i + 1)

		if (pairGroup != null && nextCard?.pairGroup == pairGroup) {
			renderItems.add(
				CardRenderItem(
					cards = listOf(currentCard, nextCard),
					isRow = true,
				),
			)
			i += 2
			continue
		}

		renderItems.add(
			CardRenderItem(
				cards = listOf(currentCard),
				isRow = false,
			),
		)
		i++
	}

	return renderItems
}

/**
 * Determine if a service-dependent card has data available to display.
 * This function is only relevant when the service is running.
 * Note: Profiles card is always available and should not use this function.
 */
fun isCardAvailableWhenServiceRunning(cardGroup: CardGroup, uiState: DashboardUiState): Boolean = when (cardGroup) {
	CardGroup.ClashMode -> uiState.clashModeVisible
	CardGroup.UploadTraffic -> uiState.trafficVisible
	CardGroup.DownloadTraffic -> uiState.trafficVisible
	CardGroup.Debug -> true // Debug info is always available when service is running
	CardGroup.Connections -> uiState.trafficVisible
	CardGroup.SystemProxy -> uiState.systemProxyVisible
	CardGroup.Profiles -> true // This shouldn't be called for Profiles, but return true for safety
}
