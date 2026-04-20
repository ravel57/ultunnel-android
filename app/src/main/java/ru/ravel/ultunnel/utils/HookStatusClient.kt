package ru.ravel.ultunnel.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import ru.ravel.ultunnel.bg.ParceledListSlice
import ru.ravel.ultunnel.xposed.HookStatusKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HookStatusClient {
    private const val TAG = "HookStatusClient"

    data class Status(val active: Boolean, val lastPatchedAt: Long, val version: Int, val systemPid: Int)

    private val statusFlow = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = statusFlow

    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        runCatching {
            refresh()
        }.onFailure {
            Log.w(TAG, "Hook status refresh skipped", it)
            statusFlow.value = null
        }
    }

    fun refresh() {
        val binder = ConnectivityBinderUtils.getBinder() ?: run {
            statusFlow.value = null
            return
        }

        runCatching {
            ConnectivityBinderUtils.withParcel { data, reply ->
                data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
                val ok = binder.transact(HookStatusKeys.TRANSACTION_STATUS, data, reply, 0)
                if (!ok) {
                    statusFlow.value = null
                    return@withParcel
                }
                reply.readException()
                statusFlow.value = Status(
                    active = reply.readInt() != 0,
                    lastPatchedAt = reply.readLong(),
                    version = reply.readInt(),
                    systemPid = reply.readInt(),
                )
            }
        }.onFailure {
            Log.w(TAG, "Hook status refresh failed", it)
            statusFlow.value = null
        }
    }

    fun getInstalledPackages(context: Context, flags: Long, userId: Int): List<PackageInfo>? {
        val binder = ConnectivityBinderUtils.getBinder() ?: return null
        return runCatching {
            ConnectivityBinderUtils.withParcel { data, reply ->
                data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
                data.writeLong(flags)
                data.writeInt(userId)
                val ok = binder.transact(HookStatusKeys.TRANSACTION_GET_INSTALLED_PACKAGES, data, reply, 0)
                if (!ok) return@withParcel null
                reply.readException()
                val slice = ParceledListSlice.CREATOR.createFromParcel(reply, PackageInfo::class.java.classLoader)
                @Suppress("UNCHECKED_CAST")
                (slice as ParceledListSlice<PackageInfo>).list
            }
        }.getOrElse {
            Log.w(TAG, "getInstalledPackages failed", it)
            null
        }
    }
}
