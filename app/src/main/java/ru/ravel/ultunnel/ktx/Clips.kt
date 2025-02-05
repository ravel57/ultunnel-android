package ru.ravel.ultunnel.ktx

import android.content.ClipData
import ru.ravel.ultunnel.Application

var clipboardText: String?
	get() = Application.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
	set(plainText) {
		if (plainText != null) {
			Application.clipboard.setPrimaryClip(ClipData.newPlainText(null, plainText))
		}
	}