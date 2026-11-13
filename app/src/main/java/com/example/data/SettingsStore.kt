package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.terDataStore: DataStore<Preferences> by preferencesDataStore(name = "ter_xtreme_prefs")

data class AppSettings(
  val theme: String = "dark",
  val fontSize: Int = 14,
  val showKeyBar: Boolean = true,
  val saveUiPrefs: Boolean = true,
  val username: String = "root",
  val hostname: String = "alpine",
  val prootNoSeccomp: Boolean = false,
  val firstBootDone: Boolean = false,
) {
  fun toJsonString(): String =
    JSONObject()
      .put("theme", theme)
      .put("font", fontSize)
      .put("keys", showKeyBar)
      .put("save", saveUiPrefs)
      .put("user", username)
      .put("host", hostname)
      .toString()

  companion object {
    fun fromJson(json: String, current: AppSettings): AppSettings =
      try {
        val obj = JSONObject(json)
        current.copy(
          theme = obj.optString("theme", current.theme),
          fontSize = obj.optInt("font", current.fontSize).coerceIn(10, 24),
          showKeyBar = obj.optBoolean("keys", current.showKeyBar),
          saveUiPrefs = obj.optBoolean("save", current.saveUiPrefs),
          username = obj.optString("user", current.username).ifBlank { "root" },
          hostname = obj.optString("host", current.hostname).ifBlank { "alpine" },
        )
      } catch (_: Throwable) {
        current
      }
  }
}

class SettingsStore(private val context: Context) {
  private object Keys {
    val THEME = stringPreferencesKey("theme")
    val FONT_SIZE = intPreferencesKey("font_size")
    val SHOW_KEY_BAR = booleanPreferencesKey("show_key_bar")
    val SAVE_UI_PREFS = booleanPreferencesKey("save_ui_prefs")
    val USERNAME = stringPreferencesKey("username")
    val HOSTNAME = stringPreferencesKey("hostname")
    val PROOT_NO_SECCOMP = booleanPreferencesKey("proot_no_seccomp")
    val FIRST_BOOT_DONE = booleanPreferencesKey("first_boot_done")
  }

  val settingsFlow: Flow<AppSettings> =
    context.terDataStore.data.map { prefs ->
      AppSettings(
        theme = prefs[Keys.THEME] ?: "dark",
        fontSize = (prefs[Keys.FONT_SIZE] ?: 14).coerceIn(10, 24),
        showKeyBar = prefs[Keys.SHOW_KEY_BAR] ?: true,
        saveUiPrefs = prefs[Keys.SAVE_UI_PREFS] ?: true,
        username = prefs[Keys.USERNAME] ?: "root",
        hostname = prefs[Keys.HOSTNAME] ?: "alpine",
        prootNoSeccomp = prefs[Keys.PROOT_NO_SECCOMP] ?: false,
        firstBootDone = prefs[Keys.FIRST_BOOT_DONE] ?: false,
      )
    }

  suspend fun getCurrent(): AppSettings = settingsFlow.first()

  suspend fun updateUiSettings(settings: AppSettings) {
    context.terDataStore.edit { prefs ->
      prefs[Keys.THEME] = settings.theme
      prefs[Keys.FONT_SIZE] = settings.fontSize.coerceIn(10, 24)
      prefs[Keys.SHOW_KEY_BAR] = settings.showKeyBar
      prefs[Keys.SAVE_UI_PREFS] = settings.saveUiPrefs
      prefs[Keys.USERNAME] = settings.username
      prefs[Keys.HOSTNAME] = settings.hostname
    }
  }

  suspend fun setProotNoSeccomp(enabled: Boolean) {
    context.terDataStore.edit { prefs ->
      prefs[Keys.PROOT_NO_SECCOMP] = enabled
    }
  }

  suspend fun setFirstBootDone(done: Boolean) {
    context.terDataStore.edit { prefs ->
      prefs[Keys.FIRST_BOOT_DONE] = done
    }
  }

  suspend fun resetUiDefaults() {
    context.terDataStore.edit { prefs ->
      prefs[Keys.THEME] = "dark"
      prefs[Keys.FONT_SIZE] = 14
      prefs[Keys.SHOW_KEY_BAR] = true
      prefs[Keys.SAVE_UI_PREFS] = true
      prefs[Keys.USERNAME] = "root"
      prefs[Keys.HOSTNAME] = "alpine"
    }
  }
}
