package com.neiro.aftertone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionRepositoryTest {
    @Test
    fun savingPaletteRestoresItInANewRepositoryInstance() {
        val storage = InMemoryThemePreferenceStorage()
        val initialRepository = ThemeSelectionRepository(storage)

        initialRepository.save(AftertonePalette.TEAL_FOREST)

        val repositoryAfterRestart = ThemeSelectionRepository(storage)
        assertEquals(AftertonePalette.TEAL_FOREST, repositoryAfterRestart.load())
    }

    @Test
    fun missingOrUnknownThemeIdFallsBackToTheOriginalAftertonePalette() {
        assertEquals(
            AftertonePalette.AFTERTONE,
            ThemeSelectionRepository(InMemoryThemePreferenceStorage()).load(),
        )
        assertEquals(
            AftertonePalette.AFTERTONE,
            ThemeSelectionRepository(InMemoryThemePreferenceStorage("removed-palette")).load(),
        )
    }

    @Test
    fun originalThemeAndAllFourReferencePalettesAreAvailable() {
        assertEquals(
            listOf("aftertone", "burgundy_mist", "teal_forest", "graphite_pearl", "rose_surf"),
            AftertonePalette.entries.map(AftertonePalette::id),
        )
    }

    private class InMemoryThemePreferenceStorage(
        private var storedThemeId: String? = null,
    ) : ThemePreferenceStorage {
        override fun readThemeId(): String? = storedThemeId

        override fun writeThemeId(themeId: String) {
            storedThemeId = themeId
        }
    }
}
