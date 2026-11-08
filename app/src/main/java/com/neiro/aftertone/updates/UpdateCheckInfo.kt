package com.neiro.aftertone.updates

internal object UpdateCheckInfo {
    const val releasePageUrl = "https://github.com/Neiro-irl/project/releases"

    fun installedVersionLabel(versionName: String): String = "Установленная версия: $versionName"
}
