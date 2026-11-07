package com.neiro.aftertone.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal interface ThemePreferenceStorage {
    fun readThemeId(): String?

    fun writeThemeId(themeId: String)
}

internal class ThemeSelectionRepository(
    private val storage: ThemePreferenceStorage,
) {
    fun load(): AftertonePalette = AftertonePalette.fromId(storage.readThemeId())

    fun save(palette: AftertonePalette) {
        storage.writeThemeId(palette.id)
    }
}

internal class SharedPreferencesThemePreferenceStorage(
    context: Context,
) : ThemePreferenceStorage {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun readThemeId(): String? = preferences.getString(THEME_ID_KEY, null)

    override fun writeThemeId(themeId: String) {
        preferences.edit { putString(THEME_ID_KEY, themeId) }
    }

    private companion object {
        const val PREFERENCES_FILE = "aftertone_theme_preferences"
        const val THEME_ID_KEY = "selected_theme_id"
    }
}
