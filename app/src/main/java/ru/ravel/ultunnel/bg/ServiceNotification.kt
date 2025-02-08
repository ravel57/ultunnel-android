package ru.ravel.ultunnel.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.Application
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.constant.Action
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.ui.MainActivity
import ru.ravel.ultunnel.utils.CommandClient

class ServiceNotification(
	private val status: MutableLiveData<Status>, private val service: Service,
) : BroadcastReceiver(), CommandClient.Handler {
	companion object {
		private const val notificationId = 1
		private const val notificationChannel = "service"
		val flags =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

		fun checkPermission(): Boolean {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
				return true
			}
			return Application.notification.areNotificationsEnabled()
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	private val commandClient =
		CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this)
	private var receiverRegistered = false

	private val notificationBuilder by lazy {
		NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
			.setContentTitle("ultunnel").setOnlyAlertOnce(true)
			.setSmallIcon(R.drawable.ic_menu)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setContentIntent(
				PendingIntent.getActivity(
					service,
					0,
					Intent(
						service,
						MainActivity::class.java
					).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
					ru.ravel.ultunnel.bg.ServiceNotification.Companion.flags
				)
			)
			.setPriority(NotificationCompat.PRIORITY_LOW).apply {
				addAction(
					NotificationCompat.Action.Builder(
						0, service.getText(R.string.stop), PendingIntent.getBroadcast(
							service,
							0,
							Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
							ru.ravel.ultunnel.bg.ServiceNotification.Companion.flags
						)
					).build()
				)
			}
	}

	fun show(lastProfileName: String, @StringRes contentTextId: Int) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Application.notification.createNotificationChannel(
				NotificationChannel(
					ru.ravel.ultunnel.bg.ServiceNotification.Companion.notificationChannel,
					"Service Notifications",
					NotificationManager.IMPORTANCE_LOW
				)
			)
		}
		service.startForeground(
			ru.ravel.ultunnel.bg.ServiceNotification.Companion.notificationId, notificationBuilder
				.setContentTitle(lastProfileName.takeIf { it.isNotBlank() } ?: "ultunnel")
				.setContentText(service.getString(contentTextId)).build()
		)
	}

	suspend fun start() {
		if (Settings.dynamicNotification) {
			commandClient.connect()
			withContext(Dispatchers.Main) {
				registerReceiver()
			}
		}
	}

	private fun registerReceiver() {
		service.registerReceiver(this, IntentFilter().apply {
			addAction(Intent.ACTION_SCREEN_ON)
			addAction(Intent.ACTION_SCREEN_OFF)
		})
		receiverRegistered = true
	}

	override fun updateStatus(status: StatusMessage) {
		val content =
			Libbox.formatBytes(status.uplink) + "/s ↑\t" + Libbox.formatBytes(status.downlink) + "/s ↓"
		Application.notificationManager.notify(
			ru.ravel.ultunnel.bg.ServiceNotification.Companion.notificationId,
			notificationBuilder.setContentText(content).build()
		)
	}

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Intent.ACTION_SCREEN_ON -> {
				commandClient.connect()
			}

			Intent.ACTION_SCREEN_OFF -> {
				commandClient.disconnect()
			}
		}
	}

	fun close() {
		commandClient.disconnect()
		ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
		if (receiverRegistered) {
			service.unregisterReceiver(this)
			receiverRegistered = false
		}
	}
}