package dev.remoty.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "remoty_prefs")

private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
private val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
private val KEY_PAIRED_DEVICES = stringPreferencesKey("paired_devices")

class RemotyPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Get or generate a stable device ID. */
    suspend fun getDeviceId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[KEY_DEVICE_ID]
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[KEY_DEVICE_ID] = newId }
        return newId
    }

    /** Get the last selected role, or null if never chosen. */
    suspend fun getRole(): DeviceRole? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_DEVICE_ROLE]?.let {
            try { DeviceRole.valueOf(it) } catch (_: Exception) { null }
        }
    }

    suspend fun setRole(role: DeviceRole) {
        context.dataStore.edit { it[KEY_DEVICE_ROLE] = role.name }
    }

    /** Get all paired devices. */
    suspend fun getPairedDevices(): List<PairedDevice> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[KEY_PAIRED_DEVICES] ?: return emptyList()
        return try {
            json.decodeFromString<List<PairedDevice>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Add or update a paired device. */
    suspend fun savePairedDevice(device: PairedDevice) {
        context.dataStore.edit { prefs ->
            val existing = prefs[KEY_PAIRED_DEVICES]?.let {
                try { json.decodeFromString<List<PairedDevice>>(it) } catch (_: Exception) { emptyList() }
            } ?: emptyList()

            val updated = existing.filter { it.id != device.id } + device
            prefs[KEY_PAIRED_DEVICES] = json.encodeToString(updated)
        }
    }

    /** Remove a paired device. */
    suspend fun removePairedDevice(deviceId: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[KEY_PAIRED_DEVICES]?.let {
                try { json.decodeFromString<List<PairedDevice>>(it) } catch (_: Exception) { emptyList() }
            } ?: emptyList()

            val updated = existing.filter { it.id != deviceId }
            prefs[KEY_PAIRED_DEVICES] = json.encodeToString(updated)
        }
    }
}
