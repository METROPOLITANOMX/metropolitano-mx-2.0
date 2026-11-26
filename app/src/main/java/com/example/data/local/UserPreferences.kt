package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "mx_pro_prefs")

data class StoredPrefs(
    val sunMode: Boolean = false,
    val activeCircuitId: String = "metropolitano_madrid",
    val rememberedRiderName: String = "",
    val rememberedPassword: String = "",
    val rememberMe: Boolean = true,
    val loggedInRiderId: String = "",
    val rememberedDirectorPass: String = "",
    val rememberDirectorPass: Boolean = true,
    val installCodeUnlocked: Boolean = false,
    val unlockedWithCode: String = "",
    val deviceId: String = "",
    val languageOverride: String? = null
)

class UserPreferences(private val context: Context) {

    private object Keys {
        val SUN_MODE = booleanPreferencesKey("sun_mode")
        val ACTIVE_CIRCUIT_ID = stringPreferencesKey("active_circuit_id")
        val REMEMBER_ME = booleanPreferencesKey("remember_me")
        val REMEMBERED_NAME = stringPreferencesKey("remembered_name")
        val REMEMBERED_PASS = stringPreferencesKey("remembered_pass")
        val LOGGED_RIDER_ID = stringPreferencesKey("logged_rider_id")
        val REMEMBER_DIR_PASS = booleanPreferencesKey("remember_dir_pass")
        val REMEMBERED_DIR_PASS = stringPreferencesKey("remembered_dir_pass")
        val INSTALL_CODE_UNLOCKED = booleanPreferencesKey("install_code_unlocked")
        val UNLOCKED_WITH_CODE = stringPreferencesKey("unlocked_with_code")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LANG_OVERRIDE = stringPreferencesKey("lang_override")
    }

    val prefsFlow: Flow<StoredPrefs> = context.dataStore.data.map { prefs ->
        StoredPrefs(
            sunMode = prefs[Keys.SUN_MODE] ?: false,
            activeCircuitId = prefs[Keys.ACTIVE_CIRCUIT_ID] ?: "metropolitano_madrid",
            rememberedRiderName = prefs[Keys.REMEMBERED_NAME] ?: "",
            rememberedPassword = prefs[Keys.REMEMBERED_PASS] ?: "",
            rememberMe = prefs[Keys.REMEMBER_ME] ?: true,
            loggedInRiderId = prefs[Keys.LOGGED_RIDER_ID] ?: "",
            rememberedDirectorPass = prefs[Keys.REMEMBERED_DIR_PASS] ?: "",
            rememberDirectorPass = prefs[Keys.REMEMBER_DIR_PASS] ?: true,
            installCodeUnlocked = prefs[Keys.INSTALL_CODE_UNLOCKED] ?: false,
            unlockedWithCode = prefs[Keys.UNLOCKED_WITH_CODE] ?: "",
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            languageOverride = prefs[Keys.LANG_OVERRIDE]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun ensureDeviceId(): String {
        var current = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.DEVICE_ID]
            if (existing.isNullOrBlank()) {
                val created = UUID.randomUUID().toString().take(12)
                prefs[Keys.DEVICE_ID] = created
                current = created
            } else {
                current = existing
            }
        }
        return current
    }

    suspend fun setSunMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUN_MODE] = enabled }
    }

    suspend fun setActiveCircuitId(circuitId: String) {
        context.dataStore.edit { it[Keys.ACTIVE_CIRCUIT_ID] = circuitId }
    }

    suspend fun setLanguageOverride(lang: String?) {
        context.dataStore.edit {
            if (lang == null) it.remove(Keys.LANG_OVERRIDE) else it[Keys.LANG_OVERRIDE] = lang
        }
    }

    suspend fun saveLoginState(
        riderId: String,
        riderName: String,
        passwordPlain: String,
        remember: Boolean
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LOGGED_RIDER_ID] = riderId
            prefs[Keys.REMEMBER_ME] = remember
            if (remember) {
                prefs[Keys.REMEMBERED_NAME] = riderName
                prefs[Keys.REMEMBERED_PASS] = passwordPlain
            } else {
                prefs.remove(Keys.REMEMBERED_NAME)
                prefs.remove(Keys.REMEMBERED_PASS)
            }
        }
    }

    suspend fun saveDirectorCreds(passwordPlain: String, remember: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REMEMBER_DIR_PASS] = remember
            if (remember) {
                prefs[Keys.REMEMBERED_DIR_PASS] = passwordPlain
            } else {
                prefs.remove(Keys.REMEMBERED_DIR_PASS)
            }
        }
    }

    suspend fun markInstallUnlocked(code: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.INSTALL_CODE_UNLOCKED] = true
            prefs[Keys.UNLOCKED_WITH_CODE] = code
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.remove(Keys.LOGGED_RIDER_ID) }
    }
}
