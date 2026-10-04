package ru.ravel.ultunnel.bg

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import ru.ravel.ultunnel.Application
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

interface PlatformInterfaceWrapper : PlatformInterface {
	override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

	override fun autoDetectInterfaceControl(fd: Int) {
	}

	override fun openTun(options: TunOptions): Int {
		error("invalid argument")
	}

	override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

	@RequiresApi(Build.VERSION_CODES.Q)
	override fun findConnectionOwner(
		ipProtocol: Int,
		sourceAddress: String,
		sourcePort: Int,
		destinationAddress: String,
		destinationPort: Int,
	): ConnectionOwner {
		return try {
			val uid =
				Application.connectivity.getConnectionOwnerUid(
					ipProtocol,
					InetSocketAddress(sourceAddress, sourcePort),
					InetSocketAddress(destinationAddress, destinationPort),
				)

			val owner = ConnectionOwner()

			if (uid == Process.INVALID_UID) {
				Log.w(
					"PlatformInterface",
					"getConnectionOwnerUid: owner not found for $sourceAddress:$sourcePort -> $destinationAddress:$destinationPort, protocol=$ipProtocol",
				)
				owner.userId = Process.INVALID_UID
				owner.userName = ""
				owner.setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
				return owner
			}

			val packages = Application.packageManager.getPackagesForUid(uid)
			owner.userId = uid
			owner.userName = packages?.firstOrNull() ?: ""
			owner.setAndroidPackageNames(StringArray((packages?.toList() ?: emptyList()).iterator()))
			owner
		} catch (e: SecurityException) {
			Log.e("PlatformInterface", "getConnectionOwnerUid security error", e)
			ConnectionOwner().apply {
				userId = Process.INVALID_UID
				userName = ""
				setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
			}
		} catch (e: IllegalArgumentException) {
			Log.e("PlatformInterface", "getConnectionOwnerUid bad arguments", e)
			ConnectionOwner().apply {
				userId = Process.INVALID_UID
				userName = ""
				setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
			}
		} catch (e: Exception) {
			Log.e("PlatformInterface", "getConnectionOwnerUid", e)
			ConnectionOwner().apply {
				userId = Process.INVALID_UID
				userName = ""
				setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
			}
		}
	}

	override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
		DefaultNetworkMonitor.setListener(listener)
	}

	override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
		DefaultNetworkMonitor.setListener(null)
	}

	override fun getInterfaces(): NetworkInterfaceIterator {
		val networks = Application.connectivity.allNetworks
		val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
		val interfaces = mutableListOf<LibboxNetworkInterface>()
		for (network in networks) {
			val boxInterface = LibboxNetworkInterface()
			val linkProperties = Application.connectivity.getLinkProperties(network) ?: continue
			val networkCapabilities =
				Application.connectivity.getNetworkCapabilities(network) ?: continue
			boxInterface.name = linkProperties.interfaceName
			val networkInterface =
				networkInterfaces.find { it.name == boxInterface.name } ?: continue
			boxInterface.dnsServer =
				StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
			boxInterface.type =
				when {
					networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
					networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
					networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
					else -> Libbox.InterfaceTypeOther
				}
			boxInterface.index = networkInterface.index
			runCatching {
				boxInterface.mtu = networkInterface.mtu
			}.onFailure {
				Log.e(
					"PlatformInterface",
					"failed to get mtu for interface ${boxInterface.name}",
					it,
				)
			}
			boxInterface.addresses =
				StringArray(
					networkInterface.interfaceAddresses.mapTo(mutableListOf()) { it.toPrefix() }
						.iterator(),
				)
			var dumpFlags = 0
			if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
				dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
			}
			if (networkInterface.isLoopback) {
				dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
			}
			if (networkInterface.isPointToPoint) {
				dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
			}
			if (networkInterface.supportsMulticast()) {
				dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
			}
			boxInterface.flags = dumpFlags
			boxInterface.metered =
				!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
			interfaces.add(boxInterface)
		}
		return InterfaceArray(interfaces.iterator())
	}

	override fun underNetworkExtension(): Boolean = false

	override fun includeAllNetworks(): Boolean = false

	override fun clearDNSCache() {
	}

	override fun readWIFIState(): WIFIState? {
		@Suppress("DEPRECATION")
		val wifiInfo =
			Application.wifiManager.connectionInfo ?: return null
		var ssid = wifiInfo.ssid
		if (ssid == "<unknown ssid>") {
			return WIFIState("", "")
		}
		if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
			ssid = ssid.substring(1, ssid.length - 1)
		}
		return WIFIState(ssid, wifiInfo.bssid)
	}

	override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

	override fun usePlatformShell(): Boolean = false

	override fun checkPlatformShell() {
		error("not supported")
	}

	override fun openShellSession(
		user: PlatformUser?,
		command: String?,
		environ: StringIterator?,
		term: String?,
		rows: Int,
		cols: Int,
	): ShellSession {
		error("not supported")
	}

	override fun readSystemSSHHostKey(): String {
		error("not supported")
	}

	override fun lookupSFTPServer(): String {
		error("not supported")
	}

	override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

	override fun usePlatformBridge(): Boolean = false

	override fun createBridge(options: BridgeOptions?): BridgeSession {
		error("not supported")
	}

	override fun lookupUser(username: String?): PlatformUser {
		error("not supported")
	}

	override fun registerMyInterface(name: String?) {
	}

	override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
	}

	override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
	}

	private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
		override fun hasNext(): Boolean = iterator.hasNext()

		override fun next(): LibboxNetworkInterface = iterator.next()
	}

	class StringArray(private val iterator: Iterator<String>) : StringIterator {
		override fun len(): Int {
			// not used by core
			return 0
		}

		override fun hasNext(): Boolean = iterator.hasNext()

		override fun next(): String = iterator.next()
	}

	private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
		"${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
	} else {
		"${address.hostAddress}/$networkPrefixLength"
	}

	private val NetworkInterface.flags: Int
		@SuppressLint("SoonBlockedPrivateApi")
		get() {
			val getFlagsMethod = NetworkInterface::class.java.getDeclaredMethod("getFlags")
			return getFlagsMethod.invoke(this) as Int
		}
}