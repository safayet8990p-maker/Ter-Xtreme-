package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.terDataStore: DataStore<Preferences> by preferencesDataStore(name = "ter_xtreme_prefs")

data class AppSettings(
  val theme: String = "auto",
  val fontSize: Int = 14,
  val showKeyBar: Boolean = true,
  val installedThemePacks: Set<String> = emptySet(),
  val linuxUpdateBannerSeen: Boolean = false,
  val prootNoSeccomp: Boolean = false,
  val firstBootDone: Boolean = false,
) {
  fun toJsonString(storageGranted: Boolean = false): String =
    JSONObject()
      .put("theme", theme)
      .put("font", fontSize)
      .put("keys", showKeyBar)
      .put("themePacks", JSONArray(installedThemePacks.sorted()))
      .put("updateSeen", linuxUpdateBannerSeen)
      .put("storageGranted", storageGranted)
      .toString()

  companion object {
    fun fromJson(json: String, current: AppSettings): AppSettings =
      try {
        val obj = JSONObject(json)
        val packsArray = obj.optJSONArray("themePacks")
        val packs =
          if (packsArray != null) {
            buildSet {
              for (i in 0 until packsArray.length()) {
                val item = packsArray.optString(i).trim()
                if (item.isNotEmpty()) add(item)
              }
            }
          } else {
            current.installedThemePacks
          }
        current.copy(
          theme = obj.optString("theme", current.theme).ifBlank { "auto" },
          fontSize = obj.optInt("font", current.fontSize).coerceIn(10, 24),
          showKeyBar = obj.optBoolean("keys", current.showKeyBar),
          installedThemePacks = packs,
          linuxUpdateBannerSeen = obj.optBoolean("updateSeen", current.linuxUpdateBannerSeen),
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
    val INSTALLED_THEME_PACKS = stringSetPreferencesKey("installed_theme_packs")
    val LINUX_UPDATE_SEEN = booleanPreferencesKey("linux_update_seen")
    val PROOT_NO_SECCOMP = booleanPreferencesKey("proot_no_seccomp")
    val FIRST_BOOT_DONE = booleanPreferencesKey("first_boot_done")
  }

  val settingsFlow: Flow<AppSettings> =
    context.terDataStore.data.map { prefs ->
      AppSettings(
        theme = prefs[Keys.THEME] ?: "auto",
        fontSize = (prefs[Keys.FONT_SIZE] ?: 14).coerceIn(10, 24),
        showKeyBar = prefs[Keys.SHOW_KEY_BAR] ?: true,
        installedThemePacks = prefs[Keys.INSTALLED_THEME_PACKS] ?: emptySet(),
        linuxUpdateBannerSeen = prefs[Keys.LINUX_UPDATE_SEEN] ?: false,
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
      prefs[Keys.INSTALLED_THEME_PACKS] = settings.installedThemePacks
      prefs[Keys.LINUX_UPDATE_SEEN] = settings.linuxUpdateBannerSeen
    }
  }

  suspend fun installThemePack(packId: String, activate: Boolean = true) {
    context.terDataStore.edit { prefs ->
      val current = prefs[Keys.INSTALLED_THEME_PACKS] ?: emptySet()
      prefs[Keys.INSTALLED_THEME_PACKS] = current + packId
      if (activate) {
        prefs[Keys.THEME] = packId
      }
    }
  }

  suspend fun markLinuxUpdateBannerSeen() {
    context.terDataStore.edit { prefs ->
      prefs[Keys.LINUX_UPDATE_SEEN] = true
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
      prefs[Keys.THEME] = "auto"
      prefs[Keys.FONT_SIZE] = 14
      prefs[Keys.SHOW_KEY_BAR] = true
      prefs[Keys.INSTALLED_THEME_PACKS] = emptySet()
    }
  }
}
