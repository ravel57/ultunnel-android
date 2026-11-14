package ru.ravel.ultunnel.bg

import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.ravel.ultunnel.Application
import ru.ravel.ultunnel.database.Settings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Lives with the service, never enables a radio or restarts a user-stopped VPN. */
internal class ConnectionRecovery(
    private val scope: CoroutineScope,
    private val probePort: () -> Int?,
    private val recover: suspend (stillValid: () -> Boolean) -> Boolean,
) {
    private var job: Job? = null
    private val activity = RecoveryConnectionActivity(scope)

    fun stop() {
        job?.cancel()
        job = null
        activity.stop()
    }

    fun runtimeReloaded() = activity.runtimeReloaded()

    fun start() {
        stop()
        job = scope.launch {
            var networkKey: String? = null
            var stableSince = SystemClock.elapsedRealtime()
            var failures = 0
            var nextProbe = 0L
            var retryDelay = 60_000L
            while (isActive) {
                delay(if (failures > 0) 2_000L else 5_000L)
                val now = SystemClock.elapsedRealtime()
                val currentNetwork = usableNetworkKey()
                if (!Settings.autoRecoveryEnabled || !Settings.startedByUser ||
                    BoxService.configCheckMode || currentNetwork == null || deviceIdle()
                ) {
                    activity.stop()
                    networkKey = null
                    failures = 0
                    stableSince = now
                    continue
                }
                activity.ensureStarted()
                if (networkKey != currentNetwork) {
                    networkKey = currentNetwork
                    stableSince = now
                    failures = 0
                    nextProbe = now + 15_000
                    retryDelay = 60_000
                    Log.i(TAG, "Underlying network changed; waiting for it to settle")
                }
                val connections = activity.snapshot()
                if (connections == null || connections.hasRecentTraffic) {
                    // Recent received user data proves progress; an open but stalled entry does not.
                    // Unknown telemetry is not proof that traffic has stopped.
                    failures = 0
                    retryDelay = 60_000L
                    nextProbe = now + 5_000L
                    continue
                }
                if (now - stableSince < 15_000 || now < nextProbe) continue
                val actionVersion = BoxService.userActionVersion.get()
                val profileId = Settings.selectedProfile
                val port = probePort() ?: continue
                val operationJob = coroutineContext[Job]!!
                val valid = {
                    operationJob.isActive && Settings.autoRecoveryEnabled && Settings.startedByUser &&
                        !BoxService.configCheckMode && !deviceIdle() &&
                        BoxService.userActionVersion.get() == actionVersion &&
                        Settings.selectedProfile == profileId && usableNetworkKey() == currentNetwork &&
                        activity.activityVersion() == connections.version && activity.snapshot()?.hasRecentTraffic != true
                }
                try {
                    val healthy = probe(port, valid)
                    if (!valid() || activity.snapshot() == null) {
                        networkKey = null
                        failures = 0
                        continue
                    }
                    if (healthy) {
                        failures = 0
                        retryDelay = 60_000
                        nextProbe = SystemClock.elapsedRealtime() + 5_000
                        continue
                    }
                    failures++
                    nextProbe = SystemClock.elapsedRealtime() + 2_000
                    if (failures < 2) continue
                    Log.w(TAG, "Two failed tunnel checks; searching for a working profile")
                    val switched = recover(valid)
                    failures = 0
                    nextProbe = SystemClock.elapsedRealtime() + if (switched) 60_000 else retryDelay
                    retryDelay = if (switched) 60_000 else (retryDelay * 2).coerceAtMost(300_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Recovery postponed: ${e.javaClass.simpleName}")
                    failures = 0
                    nextProbe = SystemClock.elapsedRealtime() + retryDelay
                    retryDelay = (retryDelay * 2).coerceAtMost(300_000)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ConnectionRecovery"

        private fun deviceIdle(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Application.powerManager.isDeviceIdleMode

        // Do not requestNetwork(): recovery must not bring cellular back after a user disabled it.
        // VALIDATED checks the physical network, not the VPN. Captive portals are not failures of a profile.
        private fun usableNetworkKey(): String? {
            val cm = Application.connectivity
            return cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) return@mapNotNull null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
                ) return@mapNotNull null
                val link = cm.getLinkProperties(network) ?: return@mapNotNull null
                if (link.linkAddresses.isEmpty()) return@mapNotNull null
                "$network:${link.interfaceName}"
            }.sorted().takeIf { it.isNotEmpty() }?.joinToString("|")
        }

        /** HTTP CONNECT resolves destination hostnames inside sing-box, never in the Android resolver. */
        suspend fun probe(port: Int, shouldContinue: () -> Boolean = { true }): Boolean {
            if (!shouldContinue()) return false
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            try {
                for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
                    if (!shouldContinue()) return false
                    val success = suspendCancellableCoroutine<Boolean> { continuation ->
                        val call = client.newCall(Request.Builder().url(url).get().build())
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                continuation.resume(false)
                            }
                            override fun onResponse(call: Call, response: Response) {
                                response.use { continuation.resume(it.code == 204) }
                            }
                        })
                    }
                    if (success) return true
                }
                return false
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
