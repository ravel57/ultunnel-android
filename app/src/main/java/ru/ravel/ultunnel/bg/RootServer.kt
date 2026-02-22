package ru.ravel.ultunnel.bg

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.ipc.RootService
import ru.ravel.ultunnel.bg.IRootService
import ru.ravel.ultunnel.BuildConfig
import ru.ravel.ultunnel.vendor.PrivilegedServiceUtils
import java.io.IOException

class RootServer : RootService() {

    private val binder = object : IRootService.Stub() {
        override fun destroy() {
            stopSelf()
        }

        override fun getInstalledPackages(flags: Int, userId: Int): ParceledListSlice<PackageInfo> {
            val allPackages = ru.ravel.ultunnel.vendor.PrivilegedServiceUtils.getInstalledPackages(flags, userId)
            return ParceledListSlice(allPackages)
        }

        override fun installPackage(apk: ParcelFileDescriptor?, size: Long, userId: Int) {
            if (apk == null) throw IOException("APK file descriptor is null")
            ru.ravel.ultunnel.vendor.PrivilegedServiceUtils.installPackage(apk, size, userId)
        }

        override fun exportDebugInfo(outputPath: String?): String = DebugInfoExporter.export(
            this@RootServer,
            outputPath!!,
            BuildConfig.APPLICATION_ID,
        )
    }

    override fun onBind(intent: Intent): IBinder = binder
}
