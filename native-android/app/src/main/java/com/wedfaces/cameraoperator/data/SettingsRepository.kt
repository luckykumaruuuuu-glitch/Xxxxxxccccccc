package com.wedfaces.cameraoperator.data

import android.content.Context

data class SettingsSnapshot(
    val serverUrl: String,
    val email: String,
    val selectedAlbumId: String?,
    val selectedAlbumName: String?,
    val testMode: Boolean,
    val allowLocalHttp: Boolean,
    val keepScreenOn: Boolean,
)

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun snapshot() = SettingsSnapshot(
        serverUrl = preferences.getString(KEY_SERVER_URL, "") ?: "",
        email = preferences.getString(KEY_EMAIL, "") ?: "",
        selectedAlbumId = preferences.getString(KEY_ALBUM_ID, null),
        selectedAlbumName = preferences.getString(KEY_ALBUM_NAME, null),
        testMode = preferences.getBoolean(KEY_TEST_MODE, false),
        allowLocalHttp = preferences.getBoolean(KEY_ALLOW_LOCAL_HTTP, false),
        keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false),
    )

    fun saveServerUrl(value: String) {
        preferences.edit().putString(KEY_SERVER_URL, value.trim()).apply()
    }

    fun saveEmail(value: String) {
        preferences.edit().putString(KEY_EMAIL, value.trim()).apply()
    }

    fun saveAlbum(albumId: String?, albumName: String?) {
        preferences.edit()
            .putString(KEY_ALBUM_ID, albumId)
            .putString(KEY_ALBUM_NAME, albumName)
            .apply()
    }

    fun setTestMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_TEST_MODE, enabled).apply()
    }

    fun setAllowLocalHttp(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_LOCAL_HTTP, enabled).apply()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_EMAIL = "email"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_ALLOW_LOCAL_HTTP = "allow_local_http"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
