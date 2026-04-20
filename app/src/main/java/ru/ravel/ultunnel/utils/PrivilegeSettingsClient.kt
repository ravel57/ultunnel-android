package ru.ravel.ultunnel.utils

import android.content.Context
import android.os.RemoteException
import android.util.Log
import ru.ravel.ultunnel.bg.PackageEntry
import ru.ravel.ultunnel.bg.ParceledListSlice
import ru.ravel.ultunnel.bg.RootClient
import ru.ravel.ultunnel.database.Settings
import ru.ravel.ultunnel.xposed.HookModuleVersion
import ru.ravel.ultunnel.xposed.HookStatusKeys

object PrivilegeSettingsClient {
    private const val TAG = "PrivilegeSettingsClient"

    @Volatile
    private var appContext: Context? = null

    data class ExportResult(val outputPath: String?, val error: String?)

    fun register(context: Context) {
        appContext = context.applicationContext
        runCatching {
            sync()
        }.onFailure {
            Log.w(TAG, "Privilege settings sync skipped", it)
        }
    }

    fun sync(): Throwable? {
        if (isVersionMismatch()) return null
        val binder = ConnectivityBinderUtils.getBinder() ?: return null

        return ConnectivityBinderUtils.withParcel { data, reply ->
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            data.writeInt(if (Settings.privilegeSettingsEnabled) 1 else 0)
            ParceledListSlice(Settings.privilegeSettingsList.map { PackageEntry(it) }).writeToParcel(data, 0)
            data.writeInt(if (Settings.privilegeSettingsInterfaceRenameEnabled) 1 else 0)
            data.writeString(Settings.privilegeSettingsInterfacePrefix)

            try {
                val ok = binder.transact(HookStatusKeys.TRANSACTION_UPDATE_PRIVILEGE_SETTINGS, data, reply, 0)
                if (!ok) {
                    val error = RemoteException("transaction not handled")
                    Log.w(TAG, "Privilege settings sync failed", error)
                    return@withParcel error
                }
                reply.readException()
                null
            } catch (e: RemoteException) {
                Log.w(TAG, "Privilege settings sync failed: remote exception", e)
                e
            } catch (e: RuntimeException) {
                Log.w(TAG, "Privilege settings sync failed: bad reply", e)
                e
            }
        }
    }

    suspend fun exportDebugInfo(outputPath: String): ExportResult = try {
        val service = RootClient.bindService()
        val path = service.exportDebugInfo(outputPath)
        ExportResult(path, null)
    } catch (e: Throwable) {
        Log.e(TAG, "Export debug info failed", e)
        ExportResult(null, e.message ?: "export failed")
    }

    private fun isVersionMismatch(): Boolean {
        val status = HookStatusClient.status.value ?: return false
        return status.version != HookModuleVersion.CURRENT
    }
}