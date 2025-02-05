package ru.ravel.ultunnel.ui.profile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.constant.EnabledType
import ru.ravel.ultunnel.database.Profile
import ru.ravel.ultunnel.database.ProfileManager
import ru.ravel.ultunnel.database.TypedProfile
import ru.ravel.ultunnel.databinding.ActivityAddProfileBinding
import ru.ravel.ultunnel.ktx.addTextChangedListener
import ru.ravel.ultunnel.ktx.errorDialogBuilder
import ru.ravel.ultunnel.ktx.removeErrorIfNotEmpty
import ru.ravel.ultunnel.ktx.showErrorIfEmpty
import ru.ravel.ultunnel.ktx.startFilesForResult
import ru.ravel.ultunnel.ktx.text
import ru.ravel.ultunnel.ui.shared.AbstractActivity
import ru.ravel.ultunnel.utils.HTTPClient
import java.io.File
import java.io.InputStream
import java.util.Date

class NewProfileActivity : AbstractActivity<ActivityAddProfileBinding>() {
	enum class FileSource(@StringRes val formattedRes: Int) {
		CreateNew(R.string.profile_source_create_new),
		Import(R.string.profile_source_import);

		fun formatted(context: Context): String {
			return context.getString(formattedRes)
		}
	}

	private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { fileURI ->
		if (fileURI != null) {
			binding.sourceURL.editText?.setText(fileURI.toString())
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setTitle(R.string.title_new_profile)

		intent.getStringExtra("importName")?.also { importName ->
			intent.getStringExtra("importURL")?.also { importURL ->
				binding.name.editText?.setText(importName)
				binding.type.text = TypedProfile.Type.Remote.getString(this)
				binding.remoteURL.editText?.setText(importURL)
				binding.localFields.isVisible = false
				binding.remoteFields.isVisible = true
				binding.autoUpdateInterval.text = "60"
			}
		}

		binding.name.removeErrorIfNotEmpty()
		binding.type.addTextChangedListener {
			when (it) {
				TypedProfile.Type.Local.getString(this) -> {
					binding.localFields.isVisible = true
					binding.remoteFields.isVisible = false
				}

				TypedProfile.Type.Remote.getString(this) -> {
					binding.localFields.isVisible = false
					binding.remoteFields.isVisible = true
					if (binding.autoUpdateInterval.text.toIntOrNull() == null) {
						binding.autoUpdateInterval.text = "60"
					}
				}
			}
		}
		binding.fileSourceMenu.addTextChangedListener {
			when (it) {
				FileSource.CreateNew.formatted(this) -> {
					binding.importFileButton.isVisible = false
					binding.sourceURL.isVisible = false
				}

				FileSource.Import.formatted(this) -> {
					binding.importFileButton.isVisible = true
					binding.sourceURL.isVisible = true
				}
			}
		}
		binding.importFileButton.setOnClickListener {
			startFilesForResult(importFile, "application/json")
		}
		binding.createProfile.setOnClickListener(this::createProfile)
		binding.autoUpdateInterval.addTextChangedListener(this::updateAutoUpdateInterval)
	}

	private fun createProfile(@Suppress("UNUSED_PARAMETER") view: View) {
		if (binding.name.showErrorIfEmpty()) {
			return
		}
		when (binding.type.text) {
			TypedProfile.Type.Local.getString(this) -> {
				when (binding.fileSourceMenu.text) {
					FileSource.Import.formatted(this) -> {
						if (binding.sourceURL.showErrorIfEmpty()) {
							return
						}
					}
				}
			}

			TypedProfile.Type.Remote.getString(this) -> {
				if (binding.remoteURL.showErrorIfEmpty()) {
					return
				}
			}
		}
		binding.progressView.isVisible = true
		lifecycleScope.launch(Dispatchers.IO) {
			runCatching {
				createProfile0()
			}.onFailure { e ->
				withContext(Dispatchers.Main) {
					binding.progressView.isVisible = false
					errorDialogBuilder(e).show()
				}
			}
		}
	}

	private suspend fun createProfile0() {
		val typedProfile = TypedProfile()
		val profile = Profile(name = binding.name.text, typed = typedProfile)
		profile.userOrder = ProfileManager.nextOrder()
		val fileID = ProfileManager.nextFileID()
		val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
		val configFile = File(configDirectory, "$fileID.json")
		typedProfile.path = configFile.path

		when (binding.type.text) {
			TypedProfile.Type.Local.getString(this) -> {
				typedProfile.type = TypedProfile.Type.Local

				when (binding.fileSourceMenu.text) {
					FileSource.CreateNew.formatted(this) -> {
						configFile.writeText("{}")
					}

					FileSource.Import.formatted(this) -> {
						val sourceURL = binding.sourceURL.text
						val content = if (sourceURL.startsWith("content://")) {
							val inputStream =
								contentResolver.openInputStream(Uri.parse(sourceURL)) as InputStream
							inputStream.use { it.bufferedReader().readText() }
						} else if (sourceURL.startsWith("file://")) {
							File(sourceURL).readText()
						} else if (sourceURL.startsWith("http://") || sourceURL.startsWith("https://")) {
							HTTPClient().use { it.getString(sourceURL) }
						} else {
							error("unsupported source: $sourceURL")
						}
						Libbox.checkConfig(content)
						configFile.writeText(content)
					}
				}
			}

			TypedProfile.Type.Remote.getString(this) -> {
				typedProfile.type = TypedProfile.Type.Remote
				val remoteURL = binding.remoteURL.text
				val content = HTTPClient().use { it.getString(remoteURL) }
				Libbox.checkConfig(content)
				configFile.writeText(content)
				typedProfile.remoteURL = remoteURL
				typedProfile.lastUpdated = Date()
				typedProfile.autoUpdate =
					EnabledType.valueOf(this, binding.autoUpdate.text).boolValue
				binding.autoUpdateInterval.text.toIntOrNull()?.also {
					typedProfile.autoUpdateInterval = it
				}
			}
		}
		ProfileManager.create(profile)
		withContext(Dispatchers.Main) {
			binding.progressView.isVisible = false
			finish()
		}
	}

	private fun updateAutoUpdateInterval(newValue: String) {
		if (newValue.isBlank()) {
			binding.autoUpdateInterval.error = getString(R.string.profile_input_required)
			return
		}
		val intValue = try {
			newValue.toInt()
		} catch (e: Exception) {
			binding.autoUpdateInterval.error = e.localizedMessage
			return
		}
		if (intValue < 15) {
			binding.autoUpdateInterval.error =
				getString(R.string.profile_auto_update_interval_minimum_hint)
			return
		}
		binding.autoUpdateInterval.error = null
	}


}