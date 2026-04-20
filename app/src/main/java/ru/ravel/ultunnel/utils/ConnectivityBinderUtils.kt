package ru.ravel.ultunnel.utils

import android.os.IBinder
import android.os.Parcel
import android.util.Log

object ConnectivityBinderUtils {
    private const val TAG = "ConnectivityBinderUtils"

    fun getBinder(): IBinder? {
        Log.w(TAG, "Connectivity binder via hidden API is disabled on this build")
        return null
    }

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
