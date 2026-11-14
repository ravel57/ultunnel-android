package ru.ravel.ultunnel.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.ravel.ultunnel.utils.ProfileCheckConfigBuilder
import java.util.concurrent.atomic.AtomicLong
import ru.ravel.ultunnel.Application
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.constant.Action
import ru.ravel.ultunnel.constant.Alert
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.database.ProfileManager
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.ktx.hasPermission
//import ru.ravel.ultunnel.ui.MainActivity
import ru.ravel.ultunnel.utils.CommandClient
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
	companion object {
		private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
		private const val TAG = "BoxService"
		internal val userActionVersion = AtomicLong()

		fun onUserProfileSelection() { userActionVersion.incrementAndGet() }
		/**
		 * Если задан — сервис запускается с этим конфигом вместо выбранного профиля
		 * (режим параллельной проверки профилей).
		 */
		@Volatile
		var configCheckContent: String? = null

		val configCheckMode: Boolean
			get() = configCheckContent != null

		@OptIn(DelicateCoroutinesApi::class)
		fun start() = GlobalScope.launch(Dispatchers.Main.immediate) {
			Settings.dataStore.initialize()
			val serviceClass =
				if (configCheckMode) {
					// Конфиг проверки профилей без TUN: хватает ProxyService,
					// режим сервиса выбранного профиля при этом не меняем.
					ProxyService::class.java
				} else {
					withContext(Dispatchers.IO) {
						Settings.rebuildServiceMode()
					}
					Settings.serviceClass()
				}
			val intent = Intent(Application.application, serviceClass)
			ContextCompat.startForegroundService(Application.application, intent)
		}

		fun stop() {
			userActionVersion.incrementAndGet()
			Application.application.sendBroadcast(
				Intent(Action.SERVICE_CLOSE).setPackage(
					Application.application.packageName,
				),
			)
		}
	}

	var fileDescriptor: ParcelFileDescriptor? = null

	private val status = MutableLiveData(Status.Stopped)
	private val binder = ServiceBinder(status)
	private val notification = ServiceNotification(status, service)
	private lateinit var commandServer: CommandServer
	private val idleModeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)
	private val runtimeMutex = Mutex()
	@Volatile private var serviceWanted = false
	@Volatile private var healthPort: Int? = null
	private var activeContent: String? = null
	private var recoveryCursor = 0
	private val recovery = ConnectionRecovery(idleModeScope, { healthPort }) { valid ->
		recoverConnection(valid)
	}

	private fun monitoredContent(content: String): String {
		healthPort = null
		if (configCheckMode || !Settings.autoRecoveryEnabled) return content
		return try {
			val prepared = RecoveryConfig.prepare(content)
			healthPort = prepared.port
			prepared.content
		} catch (e: Exception) {
			Log.w(TAG, "Automatic recovery unavailable for this profile: ${e.javaClass.simpleName}")
			content
		}
	}

	private suspend fun recoverConnection(valid: () -> Boolean): Boolean = runtimeMutex.withLock {
		if (!serviceWanted || !valid()) return@withLock false
		val originalId = Settings.selectedProfile
		val actionVersion = userActionVersion.get()
		val originalContent = activeContent ?: return@withLock false
		val originalPort = healthPort
		val allowed = {
			serviceWanted && valid() && userActionVersion.get() == actionVersion &&
				Settings.selectedProfile == originalId
		}
		if (originalPort != null && ConnectionRecovery.probe(originalPort, allowed)) return@withLock false
		if (!allowed()) return@withLock false
		val candidates = ProfileManager.list().filter { profile ->
			profile.id != originalId && runCatching {
				val config = JSONObject(File(profile.typed.path).readText())
				val inbounds = config.optJSONArray("inbounds")
				val tun = inbounds != null && (0 until inbounds.length()).any {
					inbounds.optJSONObject(it)?.optString("type") == "tun"
				}
				tun == (service is VPNService)
			}.getOrDefault(false)
		}
		if (candidates.isEmpty() || !allowed()) return@withLock false
		// Bound memory use; subsequent failed rounds continue with the next batch.
		val batch = (candidates.drop(recoveryCursor % candidates.size) +
			candidates.take(recoveryCursor % candidates.size)).take(9)
		recoveryCursor = (recoveryCursor + batch.size) % candidates.size
		val checks = ProfileCheckConfigBuilder.build(batch)
		val checkContent = checks.content ?: return@withLock false
		if (!allowed()) return@withLock false
		val merged = RecoveryConfig.withCandidates(originalContent, checkContent)
		Libbox.checkConfig(merged)
		var temporaryRuntime = false
		var committed = false
		try {
			if (!allowed()) return@withLock false
			temporaryRuntime = true
			commandServer.startOrReloadService(merged, buildOverrideOptions())
			recovery.runtimeReloaded()
			val winner = withTimeoutOrNull(60_000.milliseconds) {
				var found: Long? = null
				for (group in checks.ports.entries.chunked(3)) {
					if (!allowed()) break
					val results = coroutineScope {
						group.map { (id, port) -> async { id to ConnectionRecovery.probe(port, allowed) } }.awaitAll()
					}
					if (!allowed()) break
					found = results.firstOrNull { it.second }?.first
					if (found != null) break
				}
				found
			} ?: return@withLock false
			if (!allowed()) return@withLock false
			val profile = ProfileManager.get(winner) ?: return@withLock false
			val replacement = monitoredContent(File(profile.typed.path).readText())
			Libbox.checkConfig(replacement)
			currentCoroutineContext().ensureActive()
			if (!allowed()) return@withLock false
			commandServer.startOrReloadService(replacement, buildOverrideOptions())
			recovery.runtimeReloaded()
			// Verify the actual selected runtime too, not just its test outbound graph.
			val replacementPort = healthPort ?: return@withLock false
			if (!ConnectionRecovery.probe(replacementPort, allowed) || !allowed()) return@withLock false
			withContext(Dispatchers.Main) {
				currentCoroutineContext().ensureActive()
				if (allowed()) {
					Settings.selectedProfile = winner
					activeContent = replacement
					lastProfileName = profile.name
					committed = true
					notification.show(lastProfileName, R.string.status_started)
					ProfileManager.notifySelectionChanged()
				}
			}
			if (committed) {
				Settings.dataStore.flush()
				Log.i(TAG, "Automatic recovery selected profile $winner")
			}
			committed
		} finally {
			if (temporaryRuntime && !committed) {
				// A radio-off/manual-selection event cancels search. Restore the runtime without
				// changing the stored selection or asking Android to activate any network.
				withContext(NonCancellable) {
					healthPort = originalPort
					if (serviceWanted) {
						try {
							commandServer.startOrReloadService(originalContent, buildOverrideOptions())
							recovery.runtimeReloaded()
						} catch (e: Exception) {
							stopAndAlert(Alert.CreateService, e.message)
						}
					}
				}
			}
		}
	}


	init {
		idleModeScope.launch {
			for (idle in idleModeUpdates) {
				if (idle) {
					commandServer.pause()
				} else {
					commandServer.wake()
				}
			}
		}
	}

	private var receiverRegistered = false
	private val receiver =
		object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				when (intent.action) {
					Action.SERVICE_CLOSE -> {
						stopService()
					}

					PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
							serviceUpdateIdleMode()
						}
					}
				}
			}
		}

	private var logcatClient: CommandClient? = null

	private fun startCommandServer() {
		Libbox.promoteOOMDraft()
		Libbox.discardPowerReportDraft()
		val commandServer = CommandServer(this, platformInterface)
		commandServer.start()
		this.commandServer = commandServer
	}

	private fun startLogcatForwarder() {
		if (logcatClient != null) return
		val client = CommandClient(
			scope = CoroutineScope(Dispatchers.IO),
			connectionType = CommandClient.ConnectionType.Log,
			handler = object : CommandClient.Handler {
				override fun appendLogs(message: List<LogEntry>) {
					message.forEach { entry ->
						val priority = when (entry.level) {
							0, 1, 2 -> Log.ERROR
							3 -> Log.WARN
							4 -> Log.INFO
							else -> Log.DEBUG
						}
						Log.println(priority, "singbox", entry.message)
					}
				}
			},
		)
		logcatClient = client
		client.connect()
	}

	private fun stopLogcatForwarder() {
		logcatClient?.disconnect()
		logcatClient = null
	}

	private var lastProfileName = ""


	private fun buildOverrideOptions(): OverrideOptions {
		return OverrideOptions().apply {
			autoRedirect = Settings.autoRedirect

			if (!configCheckMode && Settings.perAppProxyEnabled) {
				val appList = Settings.getEffectivePerAppProxyList()

				if (appList.isNotEmpty()) {
					if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
						includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
					} else {
						excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
					}
				}
			}
		}
	}

	/** Возвращает (имя для уведомления, конфиг) либо null, если конфиг недоступен (alert уже показан). */
	private suspend fun loadServiceConfig(): Pair<String, String>? {
		configCheckContent?.let { checkContent ->
			return "Проверка профилей" to checkContent
		}

		val selectedProfileId = Settings.selectedProfile
		if (selectedProfileId == -1L) {
			stopAndAlert(Alert.EmptyConfiguration)
			return null
		}

		val profile = ProfileManager.get(selectedProfileId)
		if (profile == null) {
			stopAndAlert(Alert.EmptyConfiguration)
			return null
		}

		val content = File(profile.typed.path).readText()
		if (content.isBlank()) {
			stopAndAlert(Alert.EmptyConfiguration)
			return null
		}

		return profile.name to content
	}

	private suspend fun startService() = runtimeMutex.withLock {
		try {
			val (profileName, rawContent) = loadServiceConfig() ?: return@withLock
			if (!serviceWanted) return@withLock
			val content = monitoredContent(rawContent)

			lastProfileName = profileName
			withContext(Dispatchers.Main) {
				notification.show(lastProfileName, R.string.status_starting)
			}

			DefaultNetworkMonitor.start()
			Application.application.reloadSetupOptions()

			try {
				commandServer.startOrReloadService(
					content,
					buildOverrideOptions(),
				)
				startLogcatForwarder()
			} catch (e: Exception) {
				stopAndAlert(Alert.CreateService, e.message)
				return@withLock
			}

			if (commandServer.needWIFIState()) {
				val wifiPermission =
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
						android.Manifest.permission.ACCESS_FINE_LOCATION
					} else {
						android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
					}
				if (!service.hasPermission(wifiPermission)) {
					stopAndAlert(Alert.RequestLocationPermission)
					return@withLock
				}
			}

			if (!serviceWanted) return@withLock
			activeContent = content
			status.postValue(Status.Started)
			withContext(Dispatchers.Main) {
				notification.show(lastProfileName, R.string.status_started)
			}
			notification.start()
			if (!configCheckMode) recovery.start()
		} catch (e: Exception) {
			stopAndAlert(Alert.StartService, e.message)
			return@withLock
		}
	}

	override fun serviceStop() {
		serviceWanted = false
		recovery.stop()
		notification.close()
		status.postValue(Status.Starting)
		val pfd = fileDescriptor
		if (pfd != null) {
			pfd.close()
			fileDescriptor = null
		}
		closeService()
	}

	override fun serviceReload() {
		runBlocking {
			serviceReload0()
		}
	}

	suspend fun serviceReload0() {
		userActionVersion.incrementAndGet()
		recovery.stop()
		runtimeMutex.withLock {
			if (!serviceWanted) return@withLock
			val (profileName, rawContent) = loadServiceConfig() ?: return@withLock
			val content = monitoredContent(rawContent)
			lastProfileName = profileName
			try {
				commandServer.startOrReloadService(
					content,
					buildOverrideOptions(),
				)
			} catch (e: Exception) {
				stopAndAlert(Alert.CreateService, e.message)
				return@withLock
			}

			if (commandServer.needWIFIState()) {
				val wifiPermission =
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
						android.Manifest.permission.ACCESS_FINE_LOCATION
					} else {
						android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
					}
				if (!service.hasPermission(wifiPermission)) {
					stopAndAlert(Alert.RequestLocationPermission)
					return@withLock
				}
			}
			activeContent = content
			if (serviceWanted && !configCheckMode) recovery.start()
			withContext(Dispatchers.Main) { notification.show(lastProfileName, R.string.status_started) }
		}
	}

	override fun getSystemProxyStatus(): SystemProxyStatus? {
		val status = SystemProxyStatus()
		if (service is VPNService) {
			status.available = service.systemProxyAvailable
			status.enabled = service.systemProxyEnabled
		}
		return status
	}

	override fun setSystemProxyEnabled(isEnabled: Boolean) {
		serviceReload()
	}

	@RequiresApi(Build.VERSION_CODES.M)
	private fun serviceUpdateIdleMode() {
		if (::commandServer.isInitialized) {
			idleModeUpdates.trySend(Application.powerManager.isDeviceIdleMode)
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun stopService() {
		serviceWanted = false
		recovery.stop()
		if (status.value == Status.Stopped || status.value == Status.Stopping) return
		status.value = Status.Stopping
		if (receiverRegistered) {
			service.unregisterReceiver(receiver)
			receiverRegistered = false
		}
		notification.close()
		GlobalScope.launch(Dispatchers.IO) {
			runtimeMutex.withLock {
				val pfd = fileDescriptor
				if (pfd != null) {
					pfd.close()
					fileDescriptor = null
				}
				DefaultNetworkMonitor.stop()
				stopLogcatForwarder()
				if (::commandServer.isInitialized) {
					closeService()
					commandServer.close()
				}
				Libbox.discardPowerReportDraft()
				PowerReportManager.refresh()
				Settings.startedByUser = false
				Settings.dataStore.flush()
				withContext(Dispatchers.Main) {
					status.value = Status.Stopped
					service.stopSelf()
				}
			}
		}
	}

	private fun closeService() {
		runCatching {
			commandServer.closeService()
		}.onFailure {
			commandServer.setError("android: close service: ${it.message}")
		}
	}

	private suspend fun stopAndAlert(type: Alert, message: String? = null) {
		serviceWanted = false
		recovery.stop()
		Settings.startedByUser = false
		Settings.dataStore.flush()
		val pfd = fileDescriptor
		if (pfd != null) {
			pfd.close()
			fileDescriptor = null
		}
		DefaultNetworkMonitor.stop()
		if (::commandServer.isInitialized) {
			closeService()
			commandServer.close()
		}
		withContext(Dispatchers.Main) {
			if (receiverRegistered) {
				service.unregisterReceiver(receiver)
				receiverRegistered = false
			}
			notification.close()
			binder.broadcast { callback ->
				callback.onServiceAlert(type.ordinal, message)
			}
			status.value = Status.Stopped
			service.stopSelf()
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	@Suppress("SameReturnValue")
	internal fun onStartCommand(): Int {
		if (status.value != Status.Stopped) return Service.START_NOT_STICKY
		serviceWanted = true
		status.value = Status.Starting
		notification.show(lastProfileName, R.string.status_starting)

		if (!receiverRegistered) {
			ContextCompat.registerReceiver(
				service,
				receiver,
				IntentFilter().apply {
					addAction(Action.SERVICE_CLOSE)
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
					}
				},
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			receiverRegistered = true
		}

		GlobalScope.launch(Dispatchers.IO) {
			runtimeMutex.withLock {
				if (!serviceWanted) return@launch
				Settings.startedByUser = true
				try {
					Application.application.setupLibbox()
					startCommandServer()
				} catch (e: Exception) {
					stopAndAlert(Alert.StartCommandServer, e.message)
					return@launch
				}
			}
			startService()
		}
		return Service.START_NOT_STICKY
	}

	internal fun onBind(): IBinder = binder

	internal fun onDestroy() {
		serviceWanted = false
		recovery.stop()
		idleModeUpdates.cancel()
		idleModeScope.cancel()
		binder.close()
	}

	internal fun onRevoke() {
		stopService()
	}

	internal fun sendNotification(notification: Notification) {
		val channel = "notification-${notification.typeID}"
		val builder =
			NotificationCompat.Builder(service, channel).setShowWhen(false)
				.setContentTitle(notification.title).setContentText(notification.body)
				.setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
				.setCategory(NotificationCompat.CATEGORY_EVENT)
				.setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
		if (!notification.subtitle.isNullOrBlank()) {
			builder.setContentInfo(notification.subtitle)
		}
//		if (!notification.openURL.isNullOrBlank()) {
//			builder.setContentIntent(
//				PendingIntent.getActivity(
//					service,
//					0,
//					Intent(
//						service,
//						MainActivity::class.java,
//					).apply {
//						setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
//						setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
//					},
//					ServiceNotification.flags,
//				),
//			)
//		}
		GlobalScope.launch(Dispatchers.Main) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				Application.notification.createNotificationChannel(
					NotificationChannel(
						channel,
						notification.typeName,
						NotificationManager.IMPORTANCE_HIGH,
					),
				)
			}
			Application.notification.notify(notification.identifier, notification.typeID, builder.build())
		}
	}

	internal fun cancelNotification(identifier: String, typeID: Int) {
		GlobalScope.launch(Dispatchers.Main) {
			Application.notification.cancel(identifier, typeID)
		}
	}

	override fun triggerNativeCrash() {
		Thread {
			Thread.sleep(200)
			throw RuntimeException("debug native crash")
		}.start()
	}

	override fun writeDebugMessage(message: String?) {
		Log.d("sing-box", message!!)
	}

	override fun connectSSHAgent(): Int = -1
}