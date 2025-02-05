package ru.ravel.ultunnel.database.preference

import androidx.preference.PreferenceDataStore

interface OnPreferenceDataStoreChangeListener {
	fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String)
}
