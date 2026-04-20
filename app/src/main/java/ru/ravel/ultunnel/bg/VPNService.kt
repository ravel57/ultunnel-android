package ru.ravel.ultunnel.bg

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.ktx.toIpPrefix
import ru.ravel.ultunnel.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.ktx.toIpPrefix
import ru.ravel.ultunnel.ktx.toList
import ru.ravel.ultunnel.bg.PlatformInterfaceWrapper

@SuppressLint("VpnServicePolicy")
class VPNService :
	VpnService(),
	PlatformInterfaceWrapper {
	companion object {
		private const val TAG = "VPNService"
	}

	private val service = BoxService(this, this)

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

	override fun onBind(intent: Intent): IBinder {
		val binder = super.onBind(intent)
		if (binder != null) {
			return binder
		}
		return service.onBind()
	}

	override fun onDestroy() {
		service.onDestroy()
	}

	override fun onRevoke() {
		runBlocking {
			withContext(Dispatchers.Main) {
				service.onRevoke()
			}
		}
	}

	override fun autoDetectInterfaceControl(fd: Int) {
		protect(fd)
	}

	var systemProxyAvailable = false
	var systemProxyEnabled = false

	override fun openTun(options: TunOptions): Int {
		if (prepare(this) != null) error("android: missing vpn permission")

		val builder = Builder()
			.setSession("ultunnel")
			.setMtu(options.mtu)
		Log.d(TAG, "openTun: autoRoute=${options.autoRoute}, mtu=${options.mtu}")

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			builder.setMetered(false)
		}

		val inet4Address = options.inet4Address
		while (inet4Address.hasNext()) {
			val address = inet4Address.next()
			builder.addAddress(address.address(), address.prefix())
		}

		val inet6Address = options.inet6Address
		while (inet6Address.hasNext()) {
			val address = inet6Address.next()
			builder.addAddress(address.address(), address.prefix())
		}

		if (options.autoRoute) {
			builder.addDnsServer(options.dnsServerAddress.value)

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				val inet4RouteAddress = options.inet4RouteAddress
				if (inet4RouteAddress.hasNext()) {
					while (inet4RouteAddress.hasNext()) {
						builder.addRoute(inet4RouteAddress.next().toIpPrefix())
					}
				} else if (options.inet4Address.hasNext()) {
					builder.addRoute("0.0.0.0", 0)
				}

				val inet6RouteAddress = options.inet6RouteAddress
				if (inet6RouteAddress.hasNext()) {
					while (inet6RouteAddress.hasNext()) {
						builder.addRoute(inet6RouteAddress.next().toIpPrefix())
					}
				} else if (options.inet6Address.hasNext()) {
					builder.addRoute("::", 0)
				}

				val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
				while (inet4RouteExcludeAddress.hasNext()) {
					builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
				}

				val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
				while (inet6RouteExcludeAddress.hasNext()) {
					builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
				}
			} else {
				val inet4RouteAddress = options.inet4RouteRange
				if (inet4RouteAddress.hasNext()) {
					while (inet4RouteAddress.hasNext()) {
						val address = inet4RouteAddress.next()
						builder.addRoute(address.address(), address.prefix())
					}
				}

				val inet6RouteAddress = options.inet6RouteRange
				if (inet6RouteAddress.hasNext()) {
					while (inet6RouteAddress.hasNext()) {
						val address = inet6RouteAddress.next()
						builder.addRoute(address.address(), address.prefix())
					}
				}
			}
		}

	val includePackage = options.includePackage
	val excludePackage = options.excludePackage

	val hasInclude = includePackage.hasNext()
	val hasExclude = excludePackage.hasNext()

	Log.d(TAG, "openTun: hasInclude=$hasInclude, hasExclude=$hasExclude")

	when {
		hasInclude && hasExclude -> {
			Log.e(TAG, "Both includePackage and excludePackage are set. Only one mode is allowed.")
		}

		hasInclude -> {
			while (includePackage.hasNext()) {
				try {
					val nextPackage = includePackage.next()
					Log.d(TAG, "addAllowedApplication: $nextPackage")
					builder.addAllowedApplication(nextPackage)
				} catch (e: NameNotFoundException) {
					Log.e(TAG, "addAllowedApplication failed", e)
				} catch (e: Exception) {
					Log.e(TAG, "addAllowedApplication unexpected error", e)
				}
			}
		}

		hasExclude -> {
			while (excludePackage.hasNext()) {
				try {
					val nextPackage = excludePackage.next()
					Log.d(TAG, "addDisallowedApplication: $nextPackage")
					builder.addDisallowedApplication(nextPackage)
				} catch (e: NameNotFoundException) {
					Log.e(TAG, "addDisallowedApplication failed", e)
				} catch (e: Exception) {
					Log.e(TAG, "addDisallowedApplication unexpected error", e)
				}
			}
		}

		else -> {
			Log.w(TAG, "No per-app package filters passed to TunOptions")
		}
	}

		if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			systemProxyAvailable = true
			systemProxyEnabled = Settings.systemProxyEnabled
			if (systemProxyEnabled) {
				builder.setHttpProxy(
					ProxyInfo.buildDirectProxy(
						options.httpProxyServer,
						options.httpProxyServerPort,
						options.httpProxyBypassDomain.toList(),
					),
				)
			}
		} else {
			systemProxyAvailable = false
			systemProxyEnabled = false
		}

		Log.d(TAG, "openTun: establishing VPN")

		val pfd = try {
			builder.establish()
		} catch (e: Exception) {
			Log.e(TAG, "builder.establish() failed", e)
			throw e
		} ?: error("android: the application is not prepared or is revoked")

		Log.d(TAG, "openTun: VPN established, fd=${pfd.fd}")
		service.fileDescriptor = pfd
		return pfd.fd
	}

	override fun sendNotification(notification: Notification) = service.sendNotification(notification)
}