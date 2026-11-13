package com.example.veromuistio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
        val IS_VAT_REGISTERED = booleanPreferencesKey("is_vat_registered")
    }

    val isFirstRun: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_FIRST_RUN] ?: true
    }

    val isVatRegistered: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_VAT_REGISTERED] ?: true
    }

    suspend fun setFirstRun(isFirstRun: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_FIRST_RUN] = isFirstRun
        }
    }

    suspend fun setVatRegistered(isVatRegistered: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_VAT_REGISTERED] = isVatRegistered
        }
    }
}
