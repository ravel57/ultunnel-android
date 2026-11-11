package ru.ravel.ultunnel.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.bg.BoxService
import ru.ravel.ultunnel.database.Profile
import ru.ravel.ultunnel.database.ProfileManager
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.database.TypedProfile
import ru.ravel.ultunnel.model.ConfigFileFromServer
import ru.ravel.ultunnel.model.ConfigWithServerName
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ProfileConfigsUpdater {

	suspend fun refreshProfiles(context: Context) {
		withContext(Dispatchers.IO) {
			val key = Settings.accessKey.trim()
			if (key.isEmpty()) {
				withContext(Dispatchers.Main) {
					Toast.makeText(
						context,
						"Укажите ключ доступа в настройках",
						Toast.LENGTH_SHORT
					).show()
				}
				return@withContext
			}

			val url =
				"https://admin.ultunnel.ru/api/v1/get-users-proxy-servers-singbox" +
						"?secretKey=$key&platform=android"

			// Имя выбранного профиля, чтобы после пересоздания профилей выбрать тот же
			val previousSelectedName = ProfileManager.get(Settings.selectedProfile)?.name

			val fetchedData = fetchData(url)?.also {
				if (it.isNotEmpty()) {
					BoxService.stop()
					ProfileManager.list().toMutableList().forEach { p ->
						ProfileManager.delete(p)
					}
				}
			}

			fetchedData?.forEach { config ->
				val typedProfile = TypedProfile()
				val fileID = ProfileManager.nextFileID()
				val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
				val configFile = File(configDirectory, "$fileID.json")

				typedProfile.path = configFile.path

				val profile = Profile(name = config.name, typed = typedProfile)
				profile.userOrder = ProfileManager.nextOrder()

				configFile.writeText(config.content)
				ProfileManager.create(profile)
			}

			if (!fetchedData.isNullOrEmpty()) {
				// Профили пересозданы с новыми ID: старый selectedProfile больше не существует.
				val profiles = ProfileManager.list()
				val selected = profiles.firstOrNull { it.name == previousSelectedName } ?: profiles.firstOrNull()
				Settings.selectedProfile = selected?.id ?: -1L
				Settings.rebuildServiceMode()
			}

			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Профили обновлены", Toast.LENGTH_SHORT).show()
			}
		}
	}

	private suspend fun fetchData(url: String): List<ConfigFileFromServer>? {
		return withContext(Dispatchers.IO) {
			val gson = Gson()
			try {
				val connection = URL(url).openConnection() as HttpURLConnection
				connection.requestMethod = "GET"
				connection.connectTimeout = 5000
				connection.readTimeout = 5000

				if (connection.responseCode == HttpURLConnection.HTTP_OK) {
					val content = connection.inputStream.bufferedReader().readText()
					val jsonElement = JsonParser.parseString(content)

					if (jsonElement.isJsonArray) {
						return@withContext jsonElement.asJsonArray.flatMap { configWithServerName ->
							val json = gson.fromJson(configWithServerName, ConfigWithServerName::class.java)
							json.configs
								.map { gson.fromJson(it, JsonObject::class.java) }
								.map { cfgJson ->
									val normalized = MobileProfileConfig.normalize(org.json.JSONObject(cfgJson.toString()))
                                    val protocol = MobileProfileConfig.protocol(normalized)
									ConfigFileFromServer(
										content = normalized.toString(),
										name = "${json.server}-$protocol",
									)
								}
						}
					}
				}
				null
			} catch (e: Exception) {
				Log.e("ProfileConfigsUpdater.fetchData()", e.message, e)
				null
			}
		}
	}
}
