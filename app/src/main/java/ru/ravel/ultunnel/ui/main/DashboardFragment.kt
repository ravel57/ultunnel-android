package ru.ravel.ultunnel.ui.main

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.libbox.DeprecatedNoteIterator
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.bg.BoxService
import ru.ravel.ultunnel.constant.Status
import ru.ravel.ultunnel.database.Profile
import ru.ravel.ultunnel.database.ProfileManager
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.database.TypedProfile
import ru.ravel.ultunnel.databinding.FragmentDashboardBinding
import ru.ravel.ultunnel.ktx.errorDialogBuilder
import ru.ravel.ultunnel.ktx.launchCustomTab
import ru.ravel.ultunnel.model.Config
import ru.ravel.ultunnel.model.ConfigFileFromServer
import ru.ravel.ultunnel.model.ConfigWithServerName
import ru.ravel.ultunnel.ui.MainActivity
import ru.ravel.ultunnel.ui.dashboard.GroupsFragment
import ru.ravel.ultunnel.ui.dashboard.OverviewFragment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

	private val activity: MainActivity? get() = super.getActivity() as MainActivity?
	private var binding: FragmentDashboardBinding? = null
	private var mediator: TabLayoutMediator? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = FragmentDashboardBinding.inflate(inflater, container, false)
		this.binding = binding
		onCreate()
		return binding.root
	}

	private fun onCreate() {
		val activity = activity ?: return
		val binding = binding ?: return
		binding.dashboardPager.adapter = Adapter(this)
		binding.dashboardPager.offscreenPageLimit = Page.values().size
		activity.serviceStatus.observe(viewLifecycleOwner) {
			when (it) {
				Status.Stopped -> {
					disablePager()
					binding.fab.setImageResource(R.drawable.ic_play_arrow_24)
					binding.fab.show()
					binding.fab.isEnabled = true
				}

				Status.Starting -> {
					binding.fab.hide()
				}

				Status.Started -> {
					checkDeprecatedNotes()
					binding.fab.setImageResource(R.drawable.ic_stop_24)
					binding.fab.show()
					binding.fab.isEnabled = true
				}

				Status.Stopping -> {
					disablePager()
					binding.fab.hide()
				}

				else -> {}
			}
		}
		binding.fab.setOnClickListener {
			when (activity.serviceStatus.value) {
				Status.Stopped -> {
					it.isEnabled = false
					activity.startService()
				}

				Status.Started -> {
					BoxService.stop()
				}

				else -> {}
			}
		}
		binding.refresh.visibility = if (isAndroidTV(requireContext())) View.VISIBLE else View.INVISIBLE
		binding.refresh.setOnClickListener {
			updateProfiles()
		}
		binding.swiperefresh.setOnRefreshListener {
			updateProfiles()
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		mediator?.detach()
		mediator = null
		binding = null
	}

	private fun isAndroidTV(context: Context): Boolean {
		val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
		val isTvMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
		val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
		return isTvMode || hasLeanback
	}

	private fun updateProfiles() {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val url = "https://admin.ultunnel.ru/api/v1/get-users-proxy-servers-singbox?secretKey=${Settings.accessKey}"
				val fetchedData = fetchData(url).also {
					if (it?.isNotEmpty() == true) {
						BoxService.stop()
						ProfileManager.list().toMutableList().forEach { config ->
							ProfileManager.delete(config)
						}
					}
				}
				fetchedData?.forEach { config ->
					val typedProfile = TypedProfile()
					val fileID = ProfileManager.nextFileID()
					val configDirectory = File(requireContext().filesDir, "configs").also { it.mkdirs() }
					val configFile = File(configDirectory, "$fileID.json")
					typedProfile.path = configFile.path
					val profile = Profile(name = config.name, typed = typedProfile)
					profile.userOrder = ProfileManager.nextOrder()
					configFile.writeText(config.content)
					ProfileManager.create(profile)
				}
				withContext(Dispatchers.Main) {}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					requireContext().errorDialogBuilder(e).show()
				}
			} finally {
				binding?.swiperefresh?.isRefreshing = false
			}
		}
	}

	private fun checkDeprecatedNotes() {
		GlobalScope.launch(Dispatchers.IO) {
			runCatching {
				val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
				if (notes.hasNext()) {
					withContext(Dispatchers.Main) {
						loopShowDeprecatedNotes(notes)
					}
				}
			}.onFailure {
				withContext(Dispatchers.Main) {
					activity?.errorDialogBuilder(it)?.show()
				}
			}
		}
	}

	private fun loopShowDeprecatedNotes(notes: DeprecatedNoteIterator) {
		if (notes.hasNext()) {
			val note = notes.next()
			val builder = MaterialAlertDialogBuilder(requireContext())
			builder.setTitle(getString(R.string.service_error_title_deprecated_warning))
			builder.setMessage(note.message())
			builder.setPositiveButton(R.string.ok) { _, _ ->
				loopShowDeprecatedNotes(notes)
			}
			builder.setNeutralButton(R.string.service_error_deprecated_warning_documentation) { _, _ ->
				requireContext().launchCustomTab(note.migrationLink)
				loopShowDeprecatedNotes(notes)
			}
//            builder.show()
		}
	}

	private fun disablePager() {
		val binding = binding ?: return
		binding.dashboardPager.isUserInputEnabled = false
		binding.dashboardPager.setCurrentItem(0, false)
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
								.map { jsonElement ->
									val jsonConfig = gson.fromJson(jsonElement, Config::class.java)
									return@map ConfigFileFromServer(
										content = jsonElement.toString(),
										name = "${json.server}-${jsonConfig.outbounds[0].type ?: "null"}",
									)
								}
						}
					} else {
						return@withContext null
					}
				} else {
					return@withContext null
				}
			} catch (e: Exception) {
				e.printStackTrace()
				return@withContext null
			}
		}
	}

	enum class Page(@StringRes val titleRes: Int, val fragmentClass: Class<out Fragment>) {
		Overview(R.string.title_overview, OverviewFragment::class.java),
		Groups(R.string.title_groups, GroupsFragment::class.java);
	}

	class Adapter(parent: Fragment) : FragmentStateAdapter(parent) {
		override fun getItemCount(): Int {
			return Page.entries.size
		}

		override fun createFragment(position: Int): Fragment {
			return Page.entries[position].fragmentClass.getConstructor().newInstance()
		}
	}

}