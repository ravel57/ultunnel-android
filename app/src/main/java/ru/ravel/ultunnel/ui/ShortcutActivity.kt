package ru.ravel.ultunnel.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.bg.BoxService
import ru.ravel.ultunnel.bg.ServiceConnection
import ru.ravel.ultunnel.constant.Status

class ShortcutActivity : Activity(), ServiceConnection.Callback {

	private val connection = ServiceConnection(this, this, false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
			setResult(
				RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(
					this,
					ShortcutInfoCompat.Builder(this, "toggle")
						.setIntent(
							Intent(
								this,
								ShortcutActivity::class.java
							).setAction(Intent.ACTION_MAIN)
						)
						.setIcon(
							IconCompat.createWithResource(
								this,
								R.mipmap.ic_launcher
							)
						)
						.setShortLabel(getString(R.string.quick_toggle))
						.build()
				)
			)
			finish()
		} else {
			connection.connect()
			if (Build.VERSION.SDK_INT >= 25) {
				getSystemService<ShortcutManager>()?.reportShortcutUsed("toggle")
			}
		}
	}

	override fun onServiceStatusChanged(status: Status) {
		when (status) {
			Status.Started -> BoxService.stop()
			Status.Stopped -> BoxService.start()
			else -> {}
		}
		finish()
	}

	override fun onDestroy() {
		connection.disconnect()
		super.onDestroy()
	}

}