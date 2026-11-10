package ru.ravel.ultunnel.vendor

import android.app.Activity
import androidx.camera.core.ImageAnalysis
import ru.ravel.ultunnel.compose.screen.qrscan.QRCodeCropArea
import ru.ravel.ultunnel.update.UpdateInfo
import ru.ravel.ultunnel.update.UpdateSource

interface VendorInterface {
	fun checkUpdate(activity: Activity, byUser: Boolean)

	fun createQRCodeAnalyzer(
		onSuccess: (String) -> Unit,
		onFailure: (Exception) -> Unit,
		onCropArea: ((QRCodeCropArea?) -> Unit)? = null,
	): ImageAnalysis.Analyzer?

	fun isPerAppProxyAvailable(): Boolean = true

	val hasCustomUpdate: Boolean get() = false

	val updateSources: List<UpdateSource> get() = listOf(UpdateSource.GITHUB)

	fun checkUpdateAsync(): UpdateInfo? = null

	fun scheduleAutoUpdate() {}

	suspend fun verifySilentInstallMethod(method: String): Boolean = false

	/**
	 * Download and install an APK update
	 * @param context The context
	 * @param downloadUrl The URL to download the APK from
	 * @throws Exception if download or install fails
	 */
	suspend fun downloadAndInstall(context: android.content.Context, downloadUrl: String): Unit =
		throw UnsupportedOperationException("Not supported in this flavor")
}