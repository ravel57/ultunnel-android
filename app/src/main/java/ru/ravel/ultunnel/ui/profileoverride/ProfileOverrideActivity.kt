package ru.ravel.ultunnel.ui.profileoverride

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.constant.PerAppProxyUpdateType
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.databinding.ActivityConfigOverrideBinding
import ru.ravel.ultunnel.ktx.addTextChangedListener
import ru.ravel.ultunnel.ktx.setSimpleItems
import ru.ravel.ultunnel.ktx.text
import ru.ravel.ultunnel.ui.shared.AbstractActivity

class ProfileOverrideActivity :
	AbstractActivity<ActivityConfigOverrideBinding>() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setTitle(R.string.title_profile_override)
		binding.switchPerAppProxy.isChecked = Settings.perAppProxyEnabled
		binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
			Settings.perAppProxyEnabled = isChecked
			binding.perAppProxyUpdateOnChange.isEnabled = binding.switchPerAppProxy.isChecked
			binding.configureAppListButton.isEnabled = isChecked
		}
		binding.perAppProxyUpdateOnChange.isEnabled = binding.switchPerAppProxy.isChecked
		binding.configureAppListButton.isEnabled = binding.switchPerAppProxy.isChecked

		binding.perAppProxyUpdateOnChange.addTextChangedListener {
			lifecycleScope.launch(Dispatchers.IO) {
				Settings.perAppProxyUpdateOnChange =
					PerAppProxyUpdateType.valueOf(this@ProfileOverrideActivity, it).value()
			}
		}

		binding.configureAppListButton.setOnClickListener {
			startActivity(Intent(this, PerAppProxyActivity::class.java))
		}
		lifecycleScope.launch(Dispatchers.IO) {
			reloadSettings()
		}
	}

	private suspend fun reloadSettings() {
		val perAppUpdateOnChange = Settings.perAppProxyUpdateOnChange
		withContext(Dispatchers.Main) {
			binding.perAppProxyUpdateOnChange.text =
				PerAppProxyUpdateType.valueOf(perAppUpdateOnChange)
					.getString(this@ProfileOverrideActivity)
			binding.perAppProxyUpdateOnChange.setSimpleItems(R.array.per_app_proxy_update_on_change_value)
		}
	}
}