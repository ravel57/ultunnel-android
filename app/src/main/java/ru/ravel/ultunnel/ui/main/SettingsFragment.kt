//package ru.ravel.ultunnel.ui.main
//
//import android.annotation.SuppressLint
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.os.Bundle
//import android.text.Editable
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.annotation.RequiresApi
//import androidx.core.view.isGone
//import androidx.core.view.isVisible
//import androidx.core.widget.doOnTextChanged
//import androidx.fragment.app.Fragment
//import androidx.lifecycle.lifecycleScope
//import androidx.navigation.fragment.findNavController
//import io.nekohasekai.libbox.Libbox
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import ru.ravel.ultunnel.R
//import ru.ravel.ultunnel.databinding.FragmentSettingsBinding
//import ru.ravel.ultunnel.ktx.addTextChangedListener
//import ru.ravel.ultunnel.ktx.launchCustomTab
//import ru.ravel.ultunnel.ktx.setSimpleItems
//import ru.ravel.ultunnel.ktx.text
//import ru.ravel.ultunnel.ui.MainActivity
//import ru.ravel.ultunnel.ui.debug.DebugActivity
//import ru.ravel.ultunnel.ui.profileoverride.ProfileOverrideActivity
//import ru.ravel.ultunnel.vendor.Vendor
//
//class SettingsFragment : Fragment() {
//
//	private lateinit var binding: FragmentSettingsBinding
//	override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
//    ): View {
//		binding = FragmentSettingsBinding.inflate(inflater, container, false)
//		onCreate()
//		return binding.root
//	}
//
//	@RequiresApi(Build.VERSION_CODES.M)
//	private val requestIgnoreBatteryOptimizations = registerForActivityResult(
//		ActivityResultContracts.StartActivityForResult()
//	) { result ->
//		if (Application.powerManager.isIgnoringBatteryOptimizations(Application.application.packageName)) {
//			binding.backgroundPermissionCard.isGone = true
//		}
//	}
//
//	@SuppressLint("BatteryLife", "ResourceType")
//	private fun onCreate() {
//		val activity = activity as MainActivity? ?: return
//		val binding = binding ?: return
//		binding.versionText.text = Libbox.version()
//		binding.clearButton.setOnClickListener {
//			lifecycleScope.launch(Dispatchers.IO) {
//				activity.getExternalFilesDir(null)?.deleteRecursively()
//				reloadSettings()
//			}
//		}
//		if (!Vendor.checkUpdateAvailable()) {
//			binding.checkUpdateEnabled.isVisible = false
//			binding.checkUpdateButton.isVisible = false
//		}
//		binding.checkUpdateEnabled.addTextChangedListener {
//			lifecycleScope.launch(Dispatchers.IO) {
//				val newValue = EnabledType.valueOf(requireContext(), it).boolValue
//				Settings.checkUpdateEnabled = newValue
//			}
//		}
//		binding.checkUpdateButton.setOnClickListener {
//			Vendor.checkUpdate(activity, true)
//		}
//		binding.openPrivacyPolicyButton.setOnClickListener {
//			activity.launchCustomTab("https://sing-box.sagernet.org/clients/privacy/")
//		}
//		binding.disableMemoryLimit.addTextChangedListener {
//			lifecycleScope.launch(Dispatchers.IO) {
//				val newValue = EnabledType.valueOf(requireContext(), it).boolValue
//				Settings.disableMemoryLimit = !newValue
//			}
//		}
//		binding.dynamicNotificationEnabled.addTextChangedListener {
//			lifecycleScope.launch(Dispatchers.IO) {
//				val newValue = EnabledType.valueOf(requireContext(), it).boolValue
//				Settings.dynamicNotification = newValue
//			}
//		}
//
//		binding.dontKillMyAppButton.setOnClickListener {
//			it.context.launchCustomTab("https://dontkillmyapp.com/")
//		}
//
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//			binding.requestIgnoreBatteryOptimizationsButton.setOnClickListener {
//				requestIgnoreBatteryOptimizations.launch(
//					Intent(
//						android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
//						Uri.parse("package:${Application.application.packageName}")
//					)
//				)
//			}
//		}
//		binding.configureOverridesButton.setOnClickListener {
//			startActivity(Intent(requireContext(), ProfileOverrideActivity::class.java))
//		}
//		binding.openDebugButton.setOnClickListener {
//			startActivity(Intent(requireContext(), DebugActivity::class.java))
//		}
//		binding.startLogsButton.setOnClickListener {
//			findNavController().navigate(R.id.action_navigation_settings_to_navigation_log)
//		}
//		binding.accessKey.text = Editable.Factory.getInstance().newEditable(Settings.accessKey)
//		binding.accessKey.doOnTextChanged { text, _, _, _ ->
//			Settings.accessKey = text.toString()
//		}
//		lifecycleScope.launch(Dispatchers.IO) {
//			reloadSettings()
//		}
//	}
//
//	private suspend fun reloadSettings() {
//		val activity = activity ?: return
//		val binding = binding ?: return
//		val dataSize = Libbox.formatBytes(
//			(activity.getExternalFilesDir(null) ?: activity.filesDir)
//				.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
//		)
//		val checkUpdateEnabled = Settings.checkUpdateEnabled
//		val removeBackgroundPermissionPage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//			Application.powerManager.isIgnoringBatteryOptimizations(Application.application.packageName)
//		} else {
//			true
//		}
//		val dynamicNotification = Settings.dynamicNotification
//		withContext(Dispatchers.Main) {
//			binding.dataSizeText.text = dataSize
//			binding.checkUpdateEnabled.text =
//				EnabledType.from(checkUpdateEnabled).getString(requireContext())
//			binding.checkUpdateEnabled.setSimpleItems(R.array.enabled)
//			binding.disableMemoryLimit.text =
//				EnabledType.from(!Settings.disableMemoryLimit).getString(requireContext())
//			binding.disableMemoryLimit.setSimpleItems(R.array.enabled)
//			binding.backgroundPermissionCard.isGone = removeBackgroundPermissionPage
//			binding.dynamicNotificationEnabled.text =
//				EnabledType.from(dynamicNotification).getString(requireContext())
//			binding.dynamicNotificationEnabled.setSimpleItems(R.array.enabled)
//		}
//	}
//
//}