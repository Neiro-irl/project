package com.neiro.aftertone.ui

import com.neiro.aftertone.storage.BackupSummary
import com.neiro.aftertone.ui.screens.backupRestoreConfirmationCopy
import com.neiro.aftertone.ui.screens.formatPlaylistCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsCopyTest {
    @Test
    fun restoreConfirmationExplainsCountsPayloadAndReplacement() {
        val copy = backupRestoreConfirmationCopy(BackupSummary(10, 2, 3, 1_258_291))

        assertEquals("10 треков · 2 подборки · 3 трека в избранном", copy.itemCounts)
        assertEquals("Аудиофайлы и обложки: 1,2 МБ", copy.payloadDescription)
        assertTrue(copy.replacementWarning.contains("будет заменена"))
        assertTrue(copy.replacementWarning.contains("только после сохранения"))
        assertTrue(copy.replacementWarning.lowercase().contains("текущий трек остановится"))
    }

    @Test
    fun playlistCountUsesCorrectRussianPluralForms() {
        assertEquals("1 подборка", formatPlaylistCount(1))
        assertEquals("2 подборки", formatPlaylistCount(2))
        assertEquals("5 подборок", formatPlaylistCount(5))
        assertEquals("11 подборок", formatPlaylistCount(11))
        assertEquals("21 подборка", formatPlaylistCount(21))
    }
}
