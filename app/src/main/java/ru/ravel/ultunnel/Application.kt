package ru.ravel.ultunnel

//import io.nekohasekai.sfa.vendor.Vendor
import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import ru.ravel.ultunnel.bg.UpdateProfileWork
import ru.ravel.ultunnel.constant.Bugs
import ru.ravel.ultunnel.utils.AppLifecycleObserver
import ru.ravel.ultunnel.utils.HookModuleUpdateNotifier
import ru.ravel.ultunnel.utils.HookStatusClient
import ru.ravel.ultunnel.utils.PrivilegeSettingsClient
import java.io.File
import java.util.Locale

class Application : Application() {

	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		application = this
	}

	override fun onCreate() {
		super.onCreate()
		AppLifecycleObserver.register(this)

//        Seq.setContext(this)
		Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
		HookStatusClient.register(this)
		PrivilegeSettingsClient.register(this)

		@Suppress("OPT_IN_USAGE")
		GlobalScope.launch(Dispatchers.IO) {
			initialize()
			UpdateProfileWork.reconfigureUpdater()
			HookModuleUpdateNotifier.sync(this@Application)
		}

//        if (Vendor.isPerAppProxyAvailable()) {
//            registerReceiver(
//                AppChangeReceiver(),
//                IntentFilter().apply {
//                    addAction(Intent.ACTION_PACKAGE_ADDED)
//                    addAction(Intent.ACTION_PACKAGE_REPLACED)
//                    addDataScheme("package")
//                },
//            )
//        }
	}

	private fun initialize() {
		val baseDir = filesDir
		baseDir.mkdirs()
		val workingDir = getExternalFilesDir(null) ?: return
		workingDir.mkdirs()
		val tempDir = cacheDir
		tempDir.mkdirs()
		Libbox.setup(
			SetupOptions().also {
				it.basePath = baseDir.path
				it.workingPath = workingDir.path
				it.tempPath = tempDir.path
				it.fixAndroidStack = Bugs.fixAndroidStack
				it.logMaxLines = 3000
				it.debug = false
			},
		)
		Libbox.redirectStderr(File(workingDir, "stderr.log").path)
	}

	companion object {
		lateinit var application: Application
		val notification by lazy { application.getSystemService<NotificationManager>()!! }
		val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
		val packageManager by lazy { application.packageManager }
		val powerManager by lazy { application.getSystemService<PowerManager>()!! }
		val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
		val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
		val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
	}

}