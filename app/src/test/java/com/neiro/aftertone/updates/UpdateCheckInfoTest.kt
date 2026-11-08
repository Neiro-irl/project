package com.neiro.aftertone.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckInfoTest {
    @Test
    fun releasePageTargetsThePrivateRepositoryReleases() {
        assertEquals(
            "https://github.com/Neiro-irl/project/releases",
            UpdateCheckInfo.releasePageUrl,
        )
    }

    @Test
    fun installedVersionLabelShowsTheVersionFromTheCurrentApk() {
        assertEquals(
            "Установленная версия: 1.0.12",
            UpdateCheckInfo.installedVersionLabel("1.0.12"),
        )
    }
}
