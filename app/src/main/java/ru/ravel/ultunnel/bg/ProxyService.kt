package ru.ravel.ultunnel.bg

import android.app.Service
import android.content.Intent
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions

class ProxyService : Service(), PlatformInterfaceWrapper {

	private val service = BoxService(this, this)

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
		service.onStartCommand()

	override fun onBind(intent: Intent) = service.onBind()
	override fun onDestroy() = service.onDestroy()

	override fun writeLog(message: String) = service.writeLog(message)

	override fun sendNotification(notification: Notification) =
		service.sendNotification(notification)

	override fun updateRouteOptions(options: TunOptions?) {
		TODO("Not yet implemented")
	}

}