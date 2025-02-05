package ru.ravel.ultunnel.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.constant.Action
import ru.ravel.ultunnel.constant.Alert
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.database.Settings

class ServiceConnection(
	private val context: Context,
	callback: Callback,
	private val register: Boolean = true,
) : ServiceConnection {

	companion object {
		private const val TAG = "ServiceConnection"
	}

	private val callback = ServiceCallback(callback)
	private var service: ru.ravel.ultunnel.aidl.IService? = null

	val status get() = service?.status?.let { Status.values()[it] } ?: Status.Stopped

	fun connect() {
		val intent = runBlocking {
			withContext(Dispatchers.IO) {
				Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
			}
		}
		context.bindService(intent, this, AppCompatActivity.BIND_AUTO_CREATE)
		Log.d(TAG, "request connect")
	}

	fun disconnect() {
		try {
			context.unbindService(this)
		} catch (_: IllegalArgumentException) {
		}
		Log.d(TAG, "request disconnect")
	}

	fun reconnect() {
		try {
			context.unbindService(this)
		} catch (_: IllegalArgumentException) {
		}
		val intent = runBlocking {
			withContext(Dispatchers.IO) {
				Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
			}
		}
		context.bindService(intent, this, AppCompatActivity.BIND_AUTO_CREATE)
		Log.d(TAG, "request reconnect")
	}

	override fun onServiceConnected(name: ComponentName, binder: IBinder) {
		val service = ru.ravel.ultunnel.aidl.IService.Stub.asInterface(binder)
		this.service = service
		try {
			if (register) service.registerCallback(callback)
			callback.onServiceStatusChanged(service.status)
		} catch (e: RemoteException) {
			Log.e(TAG, "initialize service connection", e)
		}
		Log.d(TAG, "service connected")
	}

	override fun onServiceDisconnected(name: ComponentName?) {
		try {
			service?.unregisterCallback(callback)
		} catch (e: RemoteException) {
			Log.e(TAG, "cleanup service connection", e)
		}
		Log.d(TAG, "service disconnected")
	}

	override fun onBindingDied(name: ComponentName?) {
		reconnect()
		Log.d(TAG, "service dead")
	}

	interface Callback {
		fun onServiceStatusChanged(status: Status)
		fun onServiceAlert(type: Alert, message: String?) {}
	}

	class ServiceCallback(private val callback: Callback) : ru.ravel.ultunnel.aidl.IServiceCallback.Stub() {
		override fun onServiceStatusChanged(status: Int) {
			callback.onServiceStatusChanged(Status.values()[status])
		}

		override fun onServiceAlert(type: Int, message: String?) {
			callback.onServiceAlert(Alert.values()[type], message)
		}
	}
}