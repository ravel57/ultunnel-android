//package ru.ravel.ultunnel.ui
//
//import android.Manifest
//import android.annotation.SuppressLint
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//import android.net.VpnService
//import android.os.Build
//import android.os.Bundle
//import android.text.Html
//import androidx.activity.result.contract.ActivityResultContract
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.annotation.RequiresApi
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.MutableLiveData
//import androidx.lifecycle.lifecycleScope
//import androidx.navigation.NavController
//import androidx.navigation.NavDestination
//import androidx.navigation.findNavController
//import androidx.navigation.fragment.NavHostFragment
//import androidx.navigation.ui.AppBarConfiguration
//import androidx.navigation.ui.NavigationUI
//import androidx.navigation.ui.navigateUp
//import androidx.navigation.ui.setupActionBarWithNavController
//import androidx.navigation.ui.setupWithNavController
//import androidx.wear.compose.material.dialog.Alert
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import io.nekohasekai.libbox.Libbox
//import io.nekohasekai.libbox.ProfileContent
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import ru.ravel.ultunnel.Application
//import ru.ravel.ultunnel.R
//import ru.ravel.ultunnel.bg.ServiceConnection
//import ru.ravel.ultunnel.constant.Action
//import ru.ravel.ultunnel.constant.Alert
//import ru.ravel.ultunnel.constant.ServiceMode
//import ru.ravel.ultunnel.constant.Status
//import ru.ravel.ultunnel.database.Profile
//import ru.ravel.ultunnel.database.ProfileManager
//import ru.ravel.ultunnel.database.Settings
//import ru.ravel.ultunnel.database.TypedProfile
//import ru.ravel.ultunnel.databinding.ActivityMainBinding
//import ru.ravel.ultunnel.ktx.errorDialogBuilder
//import ru.ravel.ultunnel.ktx.hasPermission
//import ru.ravel.ultunnel.ktx.launchCustomTab
//import ru.ravel.ultunnel.ui.shared.AbstractActivity
//import ru.ravel.ultunnel.utils.MIUIUtils
////import ru.ravel.ultunnel.vendor.Vendor
//import java.io.File
//import java.util.Date
//
//class MainActivity : AbstractActivity<ActivityMainBinding>(), ServiceConnection.Callback {
//
//	companion object {
//		private const val TAG = "MainActivity"
//	}
//
//	private lateinit var navHostFragment: NavHostFragment
//	private lateinit var navController: NavController
//	private lateinit var appBarConfiguration: AppBarConfiguration
//
//	private val connection = ServiceConnection(this, this)
//
//	val serviceStatus = MutableLiveData(Status.Stopped)
//
//	override fun onCreate(savedInstanceState: Bundle?) {
//		super.onCreate(savedInstanceState)
//
//		navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_my) as NavHostFragment
//		navController = navHostFragment.navController
//		navController.setGraph(R.navigation.mobile_navigation)
//		navController.addOnDestinationChangedListener(::onDestinationChanged)
//		appBarConfiguration = AppBarConfiguration(
//			setOf(
//				R.id.navigation_dashboard,
//				R.id.navigation_settings,
//			)
//		)
//		setupActionBarWithNavController(navController, appBarConfiguration)
//		binding.navView.setupWithNavController(navController)
//		reconnect()
//		startIntegration()
//
//		onNewIntent(intent)
//
//		if (Settings.accessKey.isEmpty()) {
//			val navController = findNavController(R.id.nav_host_fragment_activity_my)
//			navController.navigate(R.id.navigation_settings)
//		}
//
//		binding.navView.setOnItemSelectedListener { item ->
//			if (Settings.accessKey.isEmpty() && item.itemId != R.id.navigation_settings) {
//				navController.navigate(R.id.navigation_settings)
//				return@setOnItemSelectedListener false
//			}
//			if (navController.currentDestination?.id == R.id.navigation_settings) {
//				navController.popBackStack(R.id.navigation_settings, true)
//			}
//			if (item.itemId == R.id.navigation_dashboard) {
//				navController.popBackStack(R.id.navigation_dashboard, true)
//				navController.navigate(R.id.navigation_dashboard)
//				return@setOnItemSelectedListener true
//			}
//			return@setOnItemSelectedListener NavigationUI.onNavDestinationSelected(item, navController)
//		}
//	}
//
//	override fun onSupportNavigateUp(): Boolean {
//		return navController.navigateUp(appBarConfiguration)
//	}
//
//	@Suppress("UNUSED_PARAMETER")
//	private fun onDestinationChanged(
//		navController: NavController,
//		navDestination: NavDestination,
//		bundle: Bundle?,
//	) {
//
//	}
//
//	override public fun onNewIntent(intent: Intent) {
//		super.onNewIntent(intent)
//		val uri = intent.data ?: return
//		when (intent.action) {
//			Action.OPEN_URL -> {
//				launchCustomTab(uri.toString())
//				return
//			}
//		}
//		if (uri.scheme == "ultunnel" && uri.host == "import-remote-profile") {
//			val profile = try {
//				Libbox.parseRemoteProfileImportLink(uri.toString())
//			} catch (e: Exception) {
//				errorDialogBuilder(e).show()
//				return
//			}
////			MaterialAlertDialogBuilder(this)
////				.setTitle(R.string.import_remote_profile)
////				.setMessage(
////					getString(
////						R.string.import_remote_profile_message,
////						profile.name,
////						profile.host
////					)
////				)
////				.setPositiveButton(R.string.ok) { _, _ ->
////					startActivity(Intent(this, NewProfileActivity::class.java).apply {
////						putExtra("importName", profile.name)
////						putExtra("importURL", profile.url)
////					})
////				}
////				.setNegativeButton(android.R.string.cancel, null)
////				.show()
//		} else if (intent.action == Intent.ACTION_VIEW) {
//			try {
//				val data = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
//				val content = Libbox.decodeProfileContent(data)
//				MaterialAlertDialogBuilder(this)
//					.setTitle(R.string.import_profile)
//					.setMessage(
//						getString(
//							R.string.import_profile_message,
//							content.name
//						)
//					)
//					.setPositiveButton(R.string.ok) { _, _ ->
//						lifecycleScope.launch {
//							withContext(Dispatchers.IO) {
//								runCatching {
//									importProfile(content)
//								}.onFailure {
//									withContext(Dispatchers.Main) {
//										errorDialogBuilder(it).show()
//									}
//								}
//							}
//						}
//					}
//					.setNegativeButton(android.R.string.cancel, null)
//					.show()
//			} catch (e: Exception) {
//				errorDialogBuilder(e).show()
//			}
//		}
//	}
//
//	private suspend fun importProfile(content: ProfileContent) {
//		val typedProfile = TypedProfile()
//		val profile = Profile(name = content.name, typed = typedProfile)
//		profile.userOrder = ProfileManager.nextOrder()
//		when (content.type) {
//			Libbox.ProfileTypeLocal -> {
//				typedProfile.type = TypedProfile.Type.Local
//			}
//
//			Libbox.ProfileTypeiCloud -> {
//				errorDialogBuilder(R.string.icloud_profile_unsupported).show()
//				return
//			}
//
//			Libbox.ProfileTypeRemote -> {
//				typedProfile.type = TypedProfile.Type.Remote
//				typedProfile.remoteURL = content.remotePath
//				typedProfile.autoUpdate = content.autoUpdate
//				typedProfile.autoUpdateInterval = content.autoUpdateInterval
//				typedProfile.lastUpdated = Date(content.lastUpdated)
//			}
//		}
//		val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
//		val configFile = File(configDirectory, "${profile.userOrder}.json")
//		configFile.writeText(content.config)
//		typedProfile.path = configFile.path
//		ProfileManager.create(profile)
//	}
//
//	fun reconnect() {
//		connection.reconnect()
//	}
//
//	private fun startIntegration() {
////		if (Vendor.checkUpdateAvailable()) {
////			lifecycleScope.launch(Dispatchers.IO) {
////				if (Settings.checkUpdateEnabled) {
////					Vendor.checkUpdate(this@MainActivity, false)
////				}
////			}
////		}
//	}
//
//	@SuppressLint("NewApi")
//	fun startService() {
//		if (!ru.ravel.ultunnel.bg.ServiceNotification.checkPermission()) {
//			notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
//			return
//		}
//
//		lifecycleScope.launch(Dispatchers.IO) {
//			if (Settings.rebuildServiceMode()) {
//				reconnect()
//			}
//			if (Settings.serviceMode == ServiceMode.VPN) {
//				if (prepare()) {
//					return@launch
//				}
//			}
//			val intent = Intent(Application.application, Settings.serviceClass())
//			withContext(Dispatchers.Main) {
//				ContextCompat.startForegroundService(Application.application, intent)
//			}
//		}
//	}
//
//	private val notificationPermissionLauncher = registerForActivityResult(
//		ActivityResultContracts.RequestPermission()
//	) {
//		if (it) {
//			startService()
//		} else {
//			onServiceAlert(Alert.RequestNotificationPermission, null)
//		}
//	}
//
//	private val locationPermissionLauncher =
//		registerForActivityResult(ActivityResultContracts.RequestPermission()) {
//			if (it) {
//				if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//					requestBackgroundLocationPermission()
//				} else {
//					startService()
//				}
//			}
//		}
//
//	private val backgroundLocationPermissionLauncher =
//		registerForActivityResult(ActivityResultContracts.RequestPermission()) {
//			if (it) {
//				startService()
//			}
//		}
//
//	private val prepareLauncher = registerForActivityResult(PrepareService()) {
//		if (it) {
//			startService()
//		} else {
//			onServiceAlert(Alert.RequestVPNPermission, null)
//		}
//	}
//
//	private class PrepareService : ActivityResultContract<Intent, Boolean>() {
//		override fun createIntent(context: Context, input: Intent): Intent {
//			return input
//		}
//
//		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
//			return resultCode == RESULT_OK
//		}
//	}
//
//	private suspend fun prepare() = withContext(Dispatchers.Main) {
//		try {
//			val intent = VpnService.prepare(this@MainActivity)
//			if (intent != null) {
//				prepareLauncher.launch(intent)
//				true
//			} else {
//				false
//			}
//		} catch (e: Exception) {
//			onServiceAlert(Alert.RequestVPNPermission, e.message)
//			false
//		}
//	}
//
//	override fun onServiceStatusChanged(status: Status) {
//		serviceStatus.postValue(status)
//	}
//
//	override fun onServiceAlert(type: Alert, message: String?) {
//		when (type) {
//			Alert.RequestLocationPermission -> {
//				return requestLocationPermission()
//			}
//
//			else -> {}
//		}
//
//		val builder = MaterialAlertDialogBuilder(this)
//		builder.setPositiveButton(R.string.ok, null)
//		when (type) {
//			Alert.RequestVPNPermission -> {
//				builder.setMessage(getString(R.string.service_error_missing_permission))
//			}
//
//			Alert.RequestNotificationPermission -> {
//				builder.setMessage(getString(R.string.service_error_missing_notification_permission))
//			}
//
//			Alert.EmptyConfiguration -> {
//				builder.setMessage(getString(R.string.service_error_empty_configuration))
//			}
//
//			Alert.StartCommandServer -> {
//				builder.setTitle(getString(R.string.service_error_title_start_command_server))
//				builder.setMessage(message)
//			}
//
//			Alert.CreateService -> {
//				builder.setTitle(getString(R.string.service_error_title_create_service))
//				builder.setMessage(message)
//			}
//
//			Alert.StartService -> {
//				builder.setTitle(getString(R.string.service_error_title_start_service))
//				builder.setMessage(message)
//
//			}
//
//			else -> {}
//		}
//		builder.show()
//	}
//
//	private fun requestLocationPermission() {
//		if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
//			requestFineLocationPermission()
//		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//			requestBackgroundLocationPermission()
//		}
//	}
//
//	private fun requestFineLocationPermission() {
//		val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//			Html.fromHtml(
//				getString(R.string.location_permission_description),
//				Html.FROM_HTML_MODE_LEGACY
//			)
//		} else {
//			@Suppress("DEPRECATION")
//			Html.fromHtml(getString(R.string.location_permission_description))
//		}
//		MaterialAlertDialogBuilder(this)
//			.setTitle(R.string.location_permission_title)
//			.setMessage(message)
//			.setPositiveButton(R.string.ok) { _, _ ->
//				requestFineLocationPermission0()
//			}
//			.setNegativeButton(R.string.no_thanks, null)
//			.setCancelable(false)
//			.show()
//	}
//
//	private fun requestFineLocationPermission0() {
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//			locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
//		} else {
//			openPermissionSettings()
//		}
//	}
//
//	@RequiresApi(Build.VERSION_CODES.Q)
//	private fun requestBackgroundLocationPermission() {
//		MaterialAlertDialogBuilder(this)
//			.setTitle(R.string.location_permission_title)
//			.setMessage(
//				Html.fromHtml(
//					getString(R.string.location_permission_background_description),
//					Html.FROM_HTML_MODE_LEGACY
//				)
//			)
//			.setPositiveButton(R.string.ok) { _, _ ->
//				backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
//			}
//			.setNegativeButton(R.string.no_thanks, null)
//			.setCancelable(false)
//			.show()
//	}
//
//	private fun openPermissionSettings() {
//		if (MIUIUtils.isMIUI) {
//			try {
//				MIUIUtils.openPermissionSettings(this)
//				return
//			} catch (ignored: Exception) {
//			}
//		}
//
//		try {
//			val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
//			intent.data = Uri.parse("package:$packageName")
//			startActivity(intent)
//		} catch (e: Exception) {
//			errorDialogBuilder(e).show()
//		}
//	}
//
//	override fun onDestroy() {
//		connection.disconnect()
//		super.onDestroy()
//	}
//
//}