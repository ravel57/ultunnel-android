package ru.ravel.ultunnel.bg

import android.os.RemoteCallbackList
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ravel.ultunnel.constant.Status

class ServiceBinder(private val status: MutableLiveData<Status>) : ru.ravel.ultunnel.aidl.IService.Stub() {
	private val callbacks = RemoteCallbackList<ru.ravel.ultunnel.aidl.IServiceCallback>()
	private val broadcastLock = Mutex()

	init {
		status.observeForever {
			broadcast { callback ->
				callback.onServiceStatusChanged(it.ordinal)
			}
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	fun broadcast(work: (ru.ravel.ultunnel.aidl.IServiceCallback) -> Unit) {
		GlobalScope.launch(Dispatchers.Main) {
			broadcastLock.withLock {
				val count = callbacks.beginBroadcast()
				try {
					repeat(count) {
						try {
							work(callbacks.getBroadcastItem(it))
						} catch (_: Exception) {
						}
					}
				} finally {
					callbacks.finishBroadcast()
				}
			}
		}
	}

	override fun getStatus(): Int {
		return (status.value ?: Status.Stopped).ordinal
	}

	override fun registerCallback(callback: ru.ravel.ultunnel.aidl.IServiceCallback) {
		callbacks.register(callback)
	}

	override fun unregisterCallback(callback: ru.ravel.ultunnel.aidl.IServiceCallback?) {
		callbacks.unregister(callback)
	}

	fun close() {
		callbacks.kill()
	}
}