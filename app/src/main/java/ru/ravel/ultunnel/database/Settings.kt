package ru.ravel.ultunnel.database

import androidx.room.Room
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.ravel.ultunnel.Application
import ru.ravel.ultunnel.bg.ProxyService
import ru.ravel.ultunnel.bg.VPNService
import ru.ravel.ultunnel.constant.Path
import ru.ravel.ultunnel.constant.ServiceMode
import ru.ravel.ultunnel.constant.SettingsKey
import ru.ravel.ultunnel.database.preference.KeyValueDatabase
import ru.ravel.ultunnel.database.preference.RoomPreferenceDataStore
import ru.ravel.ultunnel.ktx.boolean
import ru.ravel.ultunnel.ktx.int
import ru.ravel.ultunnel.ktx.long
import ru.ravel.ultunnel.ktx.string
import ru.ravel.ultunnel.ktx.stringSet
import java.io.File

object Settings {

	@OptIn(DelicateCoroutinesApi::class)
	private val instance by lazy {
		Application.application.getDatabasePath(Path.SETTINGS_DATABASE_PATH).parentFile?.mkdirs()
		Room.databaseBuilder(
			Application.application,
			KeyValueDatabase::class.java,
			Path.SETTINGS_DATABASE_PATH
		).allowMainThreadQueries()
			.fallbackToDestructiveMigration()
			.enableMultiInstanceInvalidation()
			.setQueryExecutor { GlobalScope.launch { it.run() } }
			.build()
	}
	val dataStore = RoomPreferenceDataStore(instance.keyValuePairDao())
	var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }
	var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.NORMAL }
	var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)

	var checkUpdateEnabled by dataStore.boolean(SettingsKey.CHECK_UPDATE_ENABLED) { true }
	var disableMemoryLimit by dataStore.boolean(SettingsKey.DISABLE_MEMORY_LIMIT)
	var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { true }


	const val PER_APP_PROXY_DISABLED = 0
	const val PER_APP_PROXY_EXCLUDE = 1
	const val PER_APP_PROXY_INCLUDE = 2

	var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { false }
	var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_EXCLUDE }
	var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
	var perAppProxyUpdateOnChange by dataStore.int(SettingsKey.PER_APP_PROXY_UPDATE_ON_CHANGE) { PER_APP_PROXY_DISABLED }

	var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }

	var accessKey by dataStore.string(SettingsKey.ACCESS_KEY) { "" }

	fun serviceClass(): Class<*> {
		return when (serviceMode) {
			ServiceMode.VPN -> VPNService::class.java
			else -> ProxyService::class.java
		}
	}

	suspend fun rebuildServiceMode(): Boolean {
		var newMode = ServiceMode.NORMAL
		try {
			if (needVPNService()) {
				newMode = ServiceMode.VPN
			}
		} catch (_: Exception) {
		}
		if (serviceMode == newMode) {
			return false
		}
		serviceMode = newMode
		return true
	}

	private suspend fun needVPNService(): Boolean {
		val selectedProfileId = selectedProfile
		if (selectedProfileId == -1L) return false
		val profile = ProfileManager.get(selectedProfile) ?: return false
		val content = JSONObject(File(profile.typed.path).readText())
		val inbounds = content.getJSONArray("inbounds")
		for (index in 0 until inbounds.length()) {
			val inbound = inbounds.getJSONObject(index)
			if (inbound.getString("type") == "tun") {
				return true
			}
		}
		return false
	}

}