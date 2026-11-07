package com.neiro.aftertone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.neiro.aftertone.ui.theme.AftertonePalette
import com.neiro.aftertone.ui.theme.AftertoneTheme
import com.neiro.aftertone.ui.theme.SharedPreferencesThemePreferenceStorage
import com.neiro.aftertone.ui.theme.ThemeSelectionRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val context = this@MainActivity
            val themeRepository = remember(context) {
                ThemeSelectionRepository(SharedPreferencesThemePreferenceStorage(context))
            }
            var selectedPalette by remember(themeRepository) {
                mutableStateOf(themeRepository.load())
            }
            AftertoneTheme(palette = selectedPalette) {
                AftertoneApp(
                    selectedPalette = selectedPalette,
                    onPaletteSelected = { palette ->
                        if (palette != selectedPalette) {
                            themeRepository.save(palette)
                            selectedPalette = palette
                        }
                    },
                )
            }
        }
    }
}
