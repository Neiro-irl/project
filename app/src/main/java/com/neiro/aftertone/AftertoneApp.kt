package com.neiro.aftertone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.playback.nextRepeatMode
import com.neiro.aftertone.playback.rememberMediaController
import com.neiro.aftertone.playback.rememberPlaybackUiState
import com.neiro.aftertone.storage.BackupProgress
import com.neiro.aftertone.storage.LocalLibraryRepository
import com.neiro.aftertone.storage.LibraryRestoreResult
import com.neiro.aftertone.storage.PreparedBackupArchive
import com.neiro.aftertone.storage.RestoreActionGate
import com.neiro.aftertone.storage.RestoreFinalizationResult
import com.neiro.aftertone.storage.launchRestoreOperation
import com.neiro.aftertone.ui.components.AftertoneBottomBar
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.dialogs.ConfirmDeletePlaylistDialog
import com.neiro.aftertone.ui.dialogs.ConfirmRemoveDialog
import com.neiro.aftertone.ui.dialogs.CreatePlaylistDialog
import com.neiro.aftertone.ui.dialogs.PlaylistPickerDialog
import com.neiro.aftertone.ui.screens.CollectionDetailScreen
import com.neiro.aftertone.ui.screens.CollectionsScreen
import com.neiro.aftertone.ui.screens.HomeScreen
import com.neiro.aftertone.ui.screens.LibraryScreen
import com.neiro.aftertone.ui.screens.NowPlayingScreen
import com.neiro.aftertone.ui.screens.SearchScreen
import com.neiro.aftertone.ui.screens.SettingsScreen
import com.neiro.aftertone.ui.screens.formatBackupBytes
import com.neiro.aftertone.ui.screens.BackupProgressThrottle
import com.neiro.aftertone.updates.UpdateCheckInfo
import com.neiro.aftertone.ui.theme.AftertoneBackground
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertonePalette
import com.neiro.aftertone.ui.theme.AftertoneText
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal enum class AppTab(val label: String) {
    HOME("Главная"),
    SEARCH("Поиск"),
    LIBRARY("Библиотека"),
    COLLECTIONS("Подборки"),
    SETTINGS("Настройки"),
}

private enum class Page { TAB, COLLECTION, NOW_PLAYING }
private data class Destination(val page: Page, val tab: AppTab, val collectionId: String? = null)
private const val FAVORITES_COLLECTION_ID = "__aftertone_favorites__"

@Composable
internal fun AftertoneApp(
    selectedPalette: AftertonePalette,
    onPaletteSelected: (AftertonePalette) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { LocalLibraryRepository.shared(context) }
    val restoreActionGate = repository.restoreActionGate
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = rememberMediaController(context)
    val playback = rememberPlaybackUiState(controller)

    var library by remember { mutableStateOf(LibrarySnapshot()) }
    var isLibraryLoaded by remember { mutableStateOf(false) }
    var libraryLoadError by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var openCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<String?>(null) }
    var trackToRemove by remember { mutableStateOf<Track?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var isBackupBusy by remember { mutableStateOf(false) }
    var isBackupCancelable by remember { mutableStateOf(true) }
    var backupProgressText by remember { mutableStateOf<String?>(null) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestoreBackup by remember { mutableStateOf<PreparedBackupArchive?>(null) }
    val pendingRestoreBackupOwner = repository.preparedBackupArchiveOwner
    val preparedArchiveOwnerState by pendingRestoreBackupOwner.state.collectAsState()
    var pendingRestoreCleanupId by remember { mutableStateOf(restoreActionGate.pendingCleanupTransactionId) }
    var backupCancellationToken by remember { mutableStateOf<BackupCancellationToken?>(null) }
    val latestBackupCancellationToken by rememberUpdatedState(backupCancellationToken)

    DisposableEffect(repository) {
        onDispose {
            latestBackupCancellationToken?.requestCancellation()
            pendingRestoreBackupOwner.current()?.let { prepared ->
                pendingRestoreBackupOwner.markCleanupPending(prepared)
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        pendingRestoreBackupOwner.discard(prepared) {
                            repository.discardPreparedBackup(prepared)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            library = withContext(Dispatchers.IO) { repository.load() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            libraryLoadError = true
        }
        pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
        if (pendingRestoreCleanupId != null && backupStatusMessage == null) {
            backupStatusMessage = if (pendingRestoreCleanupId == RestoreActionGate.RECOVERY_PENDING_ID) {
                "Не удалось завершить восстановление. Повтори очистку, чтобы снова управлять музыкой."
            } else {
                "Старые копии ещё сохранены после восстановления. Повтори очистку, чтобы снова управлять музыкой."
            }
        }
        isLibraryLoaded = true
    }

    LaunchedEffect(preparedArchiveOwnerState.cleanupPending, pendingRestoreCleanupId, backupStatusMessage) {
        if (pendingRestoreCleanupId == null && preparedArchiveOwnerState.cleanupPending && backupStatusMessage == null) {
            backupStatusMessage = "Не удалось удалить временный ZIP. Повтори очистку перед новой операцией."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            val counts = restoreActionGate.withLibraryAction {
                isImporting = true
                try {
                    var importedCount = 0
                    var failedCount = 0
                    uris.forEach { uri ->
                        if (restoreActionGate.isBlocked) return@forEach
                        val result = runCatching { withContext(Dispatchers.IO) { repository.import(uri) } }
                        if (result.isSuccess) importedCount++ else failedCount++
                    }
                    library = withContext(Dispatchers.IO) { repository.load() }
                    importedCount to failedCount
                } finally {
                    isImporting = false
                }
            } ?: return@launch
            val (importedCount, failedCount) = counts
            val message = when {
                failedCount == 0 && importedCount == 1 -> "Добавлен 1 трек на этот телефон."
                failedCount == 0 -> "Скопировано ${formatTrackCount(importedCount)} на этот телефон."
                importedCount == 0 -> "Не удалось импортировать эти файлы. Выбери поддерживаемые аудиофайлы и попробуй снова."
                else -> "Импортировано ${formatTrackCount(importedCount)}; не удалось прочитать ${formatTrackCount(failedCount)}."
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    fun launchImport() {
        if (!restoreActionGate.isBlocked && !isImporting && !libraryLoadError) importLauncher.launch(arrayOf("audio/*"))
    }

    fun beginBackupOperation(): BackupCancellationToken {
        val token = BackupCancellationToken()
        backupCancellationToken = token
        isBackupBusy = true
        isBackupCancelable = true
        backupProgressText = "Подготовка…"
        backupStatusMessage = null
        return token
    }

    fun finishBackupOperation(token: BackupCancellationToken) {
        if (backupCancellationToken === token) {
            backupCancellationToken = null
            isBackupBusy = false
            isBackupCancelable = true
            backupProgressText = null
        }
    }

    fun checkBackupCancellation(token: BackupCancellationToken) {
        token.checkCancelled()
    }

    fun reportBackupProgress(token: BackupCancellationToken, label: String, progress: BackupProgress) {
        val isComplete = progress.totalBytes?.let { progress.completedBytes >= it } == true
        if (!token.progressThrottle.shouldPublishAt(System.nanoTime(), force = isComplete)) return
        val message = formatBackupProgress(label, progress)
        scope.launch {
            if (backupCancellationToken === token) backupProgressText = message
        }
    }

    fun cancelBackupOperation() {
        val token = backupCancellationToken ?: return
        if (!isBackupCancelable || !token.requestCancellation()) return
        backupProgressText = "Отмена операции…"
    }

    fun startBackupExport(uri: Uri) {
        if (isBackupBusy) return
        val token = beginBackupOperation()
        backupProgressText = "Создаю ZIP-копию…"
        scope.launch {
            var notification = "Резервная копия сохранена."
            try {
                val summary = withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Could not open the selected backup destination.")
                    output.use {
                        repository.exportBackup(
                            output = it,
                            onProgress = { reportBackupProgress(token, "Создание ZIP", it) },
                            checkCancelled = { checkBackupCancellation(token) },
                        )
                    }
                }
                backupStatusMessage = "Создана копия: ${formatTrackCount(summary.trackCount)}."
            } catch (error: CancellationException) {
                backupStatusMessage = backupFailureMessage(error, restoring = false)
                notification = "Создание резервной копии отменено."
            } catch (error: Exception) {
                backupStatusMessage = backupFailureMessage(error, restoring = false)
                notification = "Не удалось создать резервную копию."
            } finally {
                finishBackupOperation(token)
            }
            snackbarHostState.showSnackbar(notification)
        }
    }

    fun inspectRestoreArchive(uri: Uri) {
        if (isBackupBusy) return
        val token = beginBackupOperation()
        backupProgressText = "Проверяю выбранный ZIP…"
        scope.launch {
            var preparedForCleanup: PreparedBackupArchive? = null
            var notification = "Архив проверен. Подтверди замену медиатеки."
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the selected archive.")
                    repository.prepareBackupArchive(
                        input = input,
                        onProgress = { reportBackupProgress(token, "Проверка ZIP", it) },
                        checkCancelled = { checkBackupCancellation(token) },
                    )
                }
                preparedForCleanup = prepared
                checkBackupCancellation(token)
                check(pendingRestoreBackupOwner.retain(prepared)) {
                    "A previous temporary restore archive still needs cleanup."
                }
                pendingRestoreBackup = prepared
                preparedForCleanup = null
            } catch (error: CancellationException) {
                backupStatusMessage = backupFailureMessage(error, restoring = true)
                notification = "Проверка архива отменена."
            } catch (error: Exception) {
                backupStatusMessage = backupFailureMessage(error, restoring = true)
                notification = "Не удалось проверить архив."
            } finally {
                preparedForCleanup?.let { prepared ->
                    val removed = runCatching {
                        withContext(NonCancellable + Dispatchers.IO) {
                            if (pendingRestoreBackupOwner.retain(prepared, cleanupPending = true)) {
                                pendingRestoreBackupOwner.discard(prepared) {
                                    repository.discardPreparedBackup(prepared)
                                }
                            } else {
                                repository.discardPreparedBackup(prepared)
                            }
                        }
                    }.getOrDefault(false)
                    if (!removed) backupStatusMessage = "Не удалось удалить временную копию из кэша приложения."
                }
                finishBackupOperation(token)
            }
            snackbarHostState.showSnackbar(notification)
        }
    }

    fun dismissRestoreConfirmation() {
        val prepared = pendingRestoreBackupOwner.current() ?: return
        pendingRestoreBackup = null
        pendingRestoreBackupOwner.markCleanupPending(prepared)
        scope.launch {
            val removed = runCatching {
                pendingRestoreBackupOwner.discard(prepared) {
                    withContext(Dispatchers.IO) { repository.discardPreparedBackup(prepared) }
                }
            }.getOrDefault(false)
            if (!removed) backupStatusMessage = "Не удалось удалить временную копию из кэша приложения."
        }
    }

    fun confirmRestore() {
        if (isBackupBusy) return
        val prepared = pendingRestoreBackupOwner.current() ?: return
        val operation = runCatching { restoreActionGate.beginRestoreOperation() }.getOrNull() ?: return
        if (!pendingRestoreBackupOwner.beginUse(prepared)) {
            operation.finish()
            return
        }
        pendingRestoreBackup = null
        showCreatePlaylist = false
        showPlaylistPicker = false
        trackToAddToPlaylist = null
        trackToRemove = null
        playlistToDelete = null
        val token = beginBackupOperation()
        backupProgressText = "Восстанавливаю аудиофайлы…"
        val restoreJob = scope.launchRestoreOperation(operation) { restoreOperation ->
            var notification = "Резервная копия восстановлена."
            var committedRestoreResult: LibraryRestoreResult? = null
            try {
                val restored = restoreActionGate.withRestoreLock {
                    captureRestoreCommit(
                        restore = {
                            repository.restoreBackup(
                                prepared = prepared,
                                onProgress = { reportBackupProgress(token, "Восстановление", it) },
                                checkCancelled = { checkBackupCancellation(token) },
                                onCommitStarted = {
                                    runBlocking(Dispatchers.Main.immediate) {
                                        if (!token.beginFinalizing()) {
                                            throw CancellationException("Restore cancelled before commit.")
                                        }
                                        if (backupCancellationToken === token) {
                                            isBackupCancelable = false
                                            backupProgressText = "Сохраняю медиатеку…"
                                        }
                                        controller?.stop()
                                        controller?.clearMediaItems()
                                    }
                                },
                            )
                        },
                        onCommitted = {
                            committedRestoreResult = it
                            restoreOperation.markCommitted(it.transactionId)
                        },
                    )
                }
                library = restored.snapshot
                libraryLoadError = false
                openCollectionId = null
                showNowPlaying = false
                val finalization = withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishRestoreOutcome(restored.transactionId).also { result ->
                        if (result.cleanupComplete) restoreOperation.markCleanupComplete()
                    }
                }
                if (finalization.rolledBack) library = restored.previousSnapshot
                pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                backupStatusMessage = when (finalization) {
                    RestoreFinalizationResult.COMMITTED_AND_CLEANED ->
                        "Восстановлено: ${formatTrackCount(restored.snapshot.tracks.size)}."
                    RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED ->
                        "Архив оказался повреждён. Предыдущая медиатека сохранена."
                    RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING ->
                        "Медиатека восстановлена, но очистка старых файлов ещё не завершена. Повтори очистку, чтобы снова управлять музыкой."
                    RestoreFinalizationResult.ROLLED_BACK_CLEANUP_PENDING ->
                        "Предыдущая медиатека восстановлена, но очистка повреждённой копии не завершена. Повтори очистку."
                    RestoreFinalizationResult.CLEANUP_PENDING ->
                        "Не удалось подтвердить целостность восстановления. Управление музыкой заблокировано до завершения очистки."
                }
                notification = when (finalization) {
                    RestoreFinalizationResult.COMMITTED_AND_CLEANED -> "Резервная копия восстановлена."
                    RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED,
                    RestoreFinalizationResult.ROLLED_BACK_CLEANUP_PENDING ->
                        "Архив повреждён; сохранена прежняя медиатека."
                    RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING,
                    RestoreFinalizationResult.CLEANUP_PENDING -> "Очистка восстановления отложена."
                }
            } catch (error: CancellationException) {
                var recoveredLibrary: LibrarySnapshot? = null
                runCatching {
                    withContext(NonCancellable + Dispatchers.IO) { repository.load() }
                }
                    .onSuccess { recoveredLibrary = it; library = it; libraryLoadError = false }
                    .onFailure {
                        if (restoreActionGate.pendingCleanupTransactionId == null) {
                            restoreOperation.markCommitted(RestoreActionGate.RECOVERY_PENDING_ID)
                        }
                        libraryLoadError = true
                    }
                pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                val cancellationCopy = restoreCancellationCopy(
                    committedResult = committedRestoreResult,
                    recoveredLibrary = recoveredLibrary,
                    cleanupPending = pendingRestoreCleanupId != null,
                )
                backupStatusMessage = cancellationCopy.statusMessage
                notification = cancellationCopy.notification
            } catch (error: Exception) {
                runCatching { withContext(Dispatchers.IO) { repository.load() } }
                    .onSuccess { library = it; libraryLoadError = false }
                    .onFailure { libraryLoadError = true }
                pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                backupStatusMessage = backupFailureMessage(error, restoring = true)
                notification = "Не удалось завершить восстановление."
            } finally {
                pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
            }
            snackbarHostState.showSnackbar(notification)
        }
        restoreJob.invokeOnCompletion {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                pendingRestoreBackupOwner.endUse(prepared)
                pendingRestoreBackupOwner.markCleanupPending(prepared)
                val archiveRemoved = runCatching {
                    pendingRestoreBackupOwner.discard(prepared) {
                        repository.discardPreparedBackup(prepared)
                    }
                }.getOrDefault(false)
                withContext(Dispatchers.Main.immediate) {
                    if (!archiveRemoved) backupStatusMessage = "Не удалось удалить временную копию из кэша приложения."
                    finishBackupOperation(token)
                }
            }
        }
    }

    fun retryRestoreCleanup() {
        if (isBackupBusy) return
        val operation = restoreActionGate.beginCleanupRetry() ?: return
        val transactionId = operation.transactionId ?: run {
            operation.finish()
            return
        }
        val token = beginBackupOperation()
        isBackupCancelable = false
        backupProgressText = "Удаляю старые копии…"
        val cleanupJob = scope.launchRestoreOperation(operation) { retryOperation ->
            try {
                if (transactionId == RestoreActionGate.RECOVERY_PENDING_ID) {
                    library = withContext(NonCancellable + Dispatchers.IO) { repository.load() }
                    libraryLoadError = false
                    pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                    backupStatusMessage = if (pendingRestoreCleanupId == null) {
                        "Восстановление проверено. Теперь можно снова управлять музыкой."
                    } else {
                        "Очистка восстановления пока не завершена; управление музыкой остаётся заблокированным. Попробуй ещё раз позже."
                    }
                } else {
                    val (finalization, refreshedLibrary) = withContext(NonCancellable + Dispatchers.IO) {
                        var result = repository.finishRestoreOutcome(transactionId)
                        var loadedLibrary = if (result.cleanupComplete || result.rolledBack) repository.load() else null
                        if (result.rolledBack && !result.cleanupComplete) {
                            val retryResult = repository.finishRestoreOutcome(transactionId)
                            result = if (retryResult.cleanupComplete) {
                                RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED
                            } else if (retryResult.rolledBack) {
                                retryResult
                            } else {
                                result
                            }
                            if (result.cleanupComplete) loadedLibrary = repository.load()
                        }
                        if (result.cleanupComplete && restoreActionGate.pendingCleanupTransactionId == transactionId) {
                            retryOperation.markCleanupComplete()
                        }
                        result to loadedLibrary
                    }
                    refreshedLibrary?.let {
                        library = it
                        libraryLoadError = false
                    }
                    pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                    backupStatusMessage = when (finalization) {
                        RestoreFinalizationResult.COMMITTED_AND_CLEANED ->
                            "Старые копии удалены. Теперь можно снова управлять музыкой."
                        RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED ->
                            "Предыдущая медиатека восстановлена; повреждённая копия удалена."
                        RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING ->
                            "Очистка пока не завершена. Сохранённые копии не удалены; попробуй ещё раз позже."
                        RestoreFinalizationResult.ROLLED_BACK_CLEANUP_PENDING ->
                            "Предыдущая медиатека восстановлена; остались файлы для очистки. Управление музыкой заблокировано."
                        RestoreFinalizationResult.CLEANUP_PENDING ->
                            "Не удалось подтвердить целостность восстановления. Управление музыкой заблокировано до очистки."
                    }
                }
            } catch (error: Exception) {
                pendingRestoreCleanupId = restoreActionGate.pendingCleanupTransactionId
                backupStatusMessage = backupFailureMessage(error, restoring = true)
            }
        }
        cleanupJob.invokeOnCompletion {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                finishBackupOperation(token)
            }
        }
    }

    fun retryPreparedArchiveCleanup() {
        if (isBackupBusy || !preparedArchiveOwnerState.cleanupPending) return
        val prepared = pendingRestoreBackupOwner.cleanupCandidate()
        val token = beginBackupOperation()
        isBackupCancelable = false
        backupProgressText = "Удаляю временную копию…"
        scope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    if (prepared == null) {
                        true
                    } else {
                        pendingRestoreBackupOwner.discard(prepared) {
                            repository.discardPreparedBackup(prepared)
                        }
                    }
                }
                val allRemoved = if (removed) {
                    withContext(Dispatchers.IO) { repository.retryPreparedBackupArchiveCleanupSweep() }
                } else {
                    false
                }
                backupStatusMessage = when {
                    !removed -> "Не удалось удалить временный ZIP. Повтори очистку позже."
                    !allRemoved -> "Один временный ZIP удалён, но осталась другая копия. Повтори очистку."
                    else -> "Временный ZIP удалён. Теперь можно продолжить."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                backupStatusMessage = backupFailureMessage(error, restoring = true)
            } finally {
                finishBackupOperation(token)
            }
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(::startBackupExport) }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::inspectRestoreArchive) }

    fun openReleasePage() {
        val intent = Intent(Intent.ACTION_VIEW, UpdateCheckInfo.releasePageUrl.toUri())
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar("Не удалось открыть GitHub Releases.") }
        }
    }

    fun playTracks(tracks: List<Track>, selectedTrackId: String) {
        restoreActionGate.runIfAllowed {
            val player = controller
            if (player == null || tracks.isEmpty()) {
                scope.launch { snackbarHostState.showSnackbar("Проигрыватель подключается. Попробуй ещё раз через секунду.") }
                return@runIfAllowed
            }
            val items = tracks.map { track ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.displayTitle)
                    .setArtist(track.displayArtist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artworkPath?.toUri())
                    .build()
                MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(File(track.filePath).toUri())
                    .setMediaMetadata(metadata)
                    .build()
            }
            val startIndex = tracks.indexOfFirst { it.id == selectedTrackId }.coerceAtLeast(0)
            player.setMediaItems(items, startIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            showNowPlaying = true
        }
    }

    fun toggleFavorite(trackId: String) {
        scope.launch {
            runCatching {
                restoreActionGate.withLibraryAction {
                    withContext(Dispatchers.IO) { repository.toggleFavorite(trackId) }
                }
            }
                .onSuccess { updated -> if (updated != null) library = updated }
                .onFailure { snackbarHostState.showSnackbar("Не удалось изменить избранное.") }
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        scope.launch {
            runCatching {
                restoreActionGate.withLibraryAction {
                    withContext(Dispatchers.IO) { repository.removeFromPlaylist(playlistId, trackId) }
                }
            }
                .onSuccess { updated ->
                    if (updated != null) {
                        library = updated
                        snackbarHostState.showSnackbar("Трек убран из подборки.")
                    }
                }
                .onFailure { snackbarHostState.showSnackbar("Не удалось изменить подборку.") }
        }
    }

    fun addToCollection(trackId: String) {
        if (restoreActionGate.isBlocked) return
        trackToAddToPlaylist = trackId
        showPlaylistPicker = library.playlists.isNotEmpty()
        if (library.playlists.isEmpty()) showCreatePlaylist = true
    }

    fun requestRemove(track: Track) {
        if (restoreActionGate.isBlocked) return
        trackToRemove = track
    }

    val destination = when {
        showNowPlaying -> Destination(Page.NOW_PLAYING, activeTab)
        openCollectionId != null -> Destination(Page.COLLECTION, activeTab, openCollectionId)
        else -> Destination(Page.TAB, activeTab)
    }
    val currentTrack = library.tracks.firstOrNull { it.id == playback.currentTrackId }
    val currentPlaylist = library.playlists.firstOrNull { it.id == destination.collectionId }

    BackHandler(enabled = showNowPlaying || openCollectionId != null) {
        if (showNowPlaying) showNowPlaying = false else openCollectionId = null
    }

    Scaffold(
        containerColor = AftertoneBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(containerColor = AftertoneSurfaceHigh, contentColor = AftertoneText) { Text(data.visuals.message) }
            }
        },
        bottomBar = {
            if (destination.page != Page.NOW_PLAYING && !libraryLoadError) {
                AftertoneBottomBar(
                    selectedTab = activeTab,
                    collectionOpen = openCollectionId != null,
                    currentTrack = currentTrack,
                    isPlaying = playback.isPlaying,
                    currentPositionMs = playback.currentPositionMs,
                    durationMs = playback.durationMs.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0L,
                    onTabSelected = { tab -> restoreActionGate.runIfAllowed { activeTab = tab; openCollectionId = null } },
                    onOpenNowPlaying = { restoreActionGate.runIfAllowed { showNowPlaying = true } },
                    onPlayPause = {
                        restoreActionGate.runIfAllowed {
                            if (controller?.isPlaying == true) controller.pause() else controller?.play()
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        val safeContent = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .statusBarsPadding()
            .then(if (destination.page == Page.NOW_PLAYING) Modifier.navigationBarsPadding() else Modifier)
        Box(modifier = safeContent) {
            if (!isLibraryLoaded) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            } else if (libraryLoadError) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Не удалось открыть медиатеку", color = AftertoneText, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (pendingRestoreCleanupId != null) {
                            "Восстановление ещё не подтверждено. Управление музыкой заблокировано, пока проверяется журнал. Повтори восстановление."
                        } else {
                            "Сохранённый список не изменён, а аудиофайлы не удалены. Изменения отключены, чтобы не потерять данные. Не удаляй Aftertone."
                        },
                        color = AftertoneMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pendingRestoreCleanupId != null) {
                        Button(onClick = ::retryRestoreCleanup, enabled = !isBackupBusy) {
                            Text(if (isBackupBusy) "Проверяю восстановление…" else "Повторить восстановление")
                        }
                    }
                }
            } else {
                AnimatedContent(
                    targetState = destination,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(210)) + slideInHorizontally(tween(210)) { it / 24 }) togetherWith
                            (fadeOut(tween(130)) + slideOutHorizontally(tween(150)) { -it / 36 })
                    },
                    label = "aftertone-screen-transition",
                ) { screen ->
                    when (screen.page) {
                        Page.NOW_PLAYING -> NowPlayingScreen(
                            track = currentTrack,
                            isFavorite = currentTrack?.id in library.favoriteTrackIds,
                            isPlaying = playback.isPlaying,
                            currentPositionMs = playback.currentPositionMs,
                            durationMs = playback.durationMs,
                            queueSize = playback.queueSize,
                            shuffleModeEnabled = playback.shuffleModeEnabled,
                            repeatMode = playback.repeatMode,
                            onBack = { showNowPlaying = false },
                            onToggleFavorite = { currentTrack?.let { toggleFavorite(it.id) } },
                            onTogglePlayPause = {
                                restoreActionGate.runIfAllowed {
                                    if (controller?.isPlaying == true) controller.pause() else controller?.play()
                                }
                            },
                            onToggleShuffle = {
                                restoreActionGate.runIfAllowed { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
                            },
                            onToggleRepeat = {
                                restoreActionGate.runIfAllowed { controller?.let { it.repeatMode = nextRepeatMode(it.repeatMode) } }
                            },
                            onPrevious = {
                                restoreActionGate.runIfAllowed {
                                    if (controller?.hasPreviousMediaItem() == true) controller.seekToPreviousMediaItem()
                                }
                            },
                            onNext = {
                                restoreActionGate.runIfAllowed {
                                    if (controller?.hasNextMediaItem() == true) controller.seekToNextMediaItem()
                                }
                            },
                            onSeek = { position -> restoreActionGate.runIfAllowed { controller?.seekTo(position) } },
                        )
                        Page.COLLECTION -> CollectionDetailScreen(
                            library = library,
                            playlist = currentPlaylist,
                            favorites = screen.collectionId == FAVORITES_COLLECTION_ID,
                            onBack = { openCollectionId = null },
                            onPlayTracks = ::playTracks,
                            onToggleFavorite = ::toggleFavorite,
                            onAddToCollection = ::addToCollection,
                            onRemoveFromDevice = ::requestRemove,
                            onRemoveFromPlaylist = ::removeFromPlaylist,
                        )
                        Page.TAB -> when (screen.tab) {
                            AppTab.HOME -> HomeScreen(
                                library = library,
                                isImporting = isImporting,
                                onImport = ::launchImport,
                                onOpenLibrary = { activeTab = AppTab.LIBRARY; openCollectionId = null },
                                onOpenCollections = { activeTab = AppTab.COLLECTIONS; openCollectionId = null },
                                onPlayTracks = ::playTracks,
                                onToggleFavorite = ::toggleFavorite,
                                onAddToCollection = ::addToCollection,
                                onRemoveFromDevice = ::requestRemove,
                            )
                            AppTab.LIBRARY -> LibraryScreen(
                                library = library,
                                isImporting = isImporting,
                                onImport = ::launchImport,
                                onPlayTracks = ::playTracks,
                                onToggleFavorite = ::toggleFavorite,
                                onAddToCollection = ::addToCollection,
                                onRemoveFromDevice = ::requestRemove,
                            )
                            AppTab.SEARCH -> SearchScreen(
                                library = library,
                                isImporting = isImporting,
                                onImport = ::launchImport,
                                onPlayTracks = ::playTracks,
                                onToggleFavorite = ::toggleFavorite,
                                onAddToCollection = ::addToCollection,
                                onRemoveFromDevice = ::requestRemove,
                            )
                            AppTab.COLLECTIONS -> CollectionsScreen(
                                library = library,
                                onOpenFavorites = { openCollectionId = FAVORITES_COLLECTION_ID },
                                onOpenPlaylist = { playlist -> openCollectionId = playlist.id },
                                onCreatePlaylist = {
                                    if (!restoreActionGate.isBlocked) {
                                        trackToAddToPlaylist = null
                                        showCreatePlaylist = true
                                    }
                                },
                                onRequestDeletePlaylist = { if (!restoreActionGate.isBlocked) playlistToDelete = it },
                            )
                            AppTab.SETTINGS -> SettingsScreen(
                                selectedPalette = selectedPalette,
                                onPaletteSelected = onPaletteSelected,
                                installedVersion = BuildConfig.VERSION_NAME,
                                onCheckForUpdates = ::openReleasePage,
                                isBackupBusy = isBackupBusy,
                                isBackupCancelable = isBackupCancelable,
                                backupProgressText = backupProgressText,
                                backupStatusMessage = backupStatusMessage,
                                restoreSummary = pendingRestoreBackup?.summary,
                                isRestoreCleanupPending = pendingRestoreCleanupId != null,
                                isTemporaryArchiveCleanupPending = preparedArchiveOwnerState.cleanupPending,
                                onCreateBackup = { if (!isBackupBusy) createBackupLauncher.launch("Aftertone_backup.zip") },
                                onSelectRestore = { if (!isBackupBusy) restoreBackupLauncher.launch(arrayOf("*/*")) },
                                onCancelBackup = ::cancelBackupOperation,
                                onConfirmRestore = ::confirmRestore,
                                onDismissRestore = ::dismissRestoreConfirmation,
                                onRetryRestoreCleanup = ::retryRestoreCleanup,
                                onRetryTemporaryArchiveCleanup = ::retryPreparedArchiveCleanup,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreatePlaylist = false
                showPlaylistPicker = false
                trackToAddToPlaylist = null
            },
            onCreate = { name ->
                if (!restoreActionGate.isBlocked) {
                    showCreatePlaylist = false
                    scope.launch {
                        val trackId = trackToAddToPlaylist
                        var created: Playlist? = null
                        val result = runCatching {
                            restoreActionGate.withLibraryAction {
                                val oldIds = library.playlists.mapTo(hashSetOf(), Playlist::id)
                                var updated = withContext(Dispatchers.IO) { repository.addPlaylist(name) }
                                created = updated.playlists.firstOrNull { it.id !in oldIds }
                                if (created != null && trackId != null) {
                                    updated = withContext(Dispatchers.IO) {
                                        repository.addToPlaylist(created!!.id, trackId)
                                    }
                                }
                                updated
                            }
                        }
                        result.onSuccess { updated ->
                            if (updated != null) {
                                library = updated
                                if (created != null && trackId != null) {
                                    trackToAddToPlaylist = null
                                    snackbarHostState.showSnackbar("Подборка создана, трек добавлен.")
                                } else if (created != null) {
                                    snackbarHostState.showSnackbar("Подборка создана.")
                                } else {
                                    snackbarHostState.showSnackbar("Подборка с таким названием уже есть.")
                                }
                            }
                        }.onFailure { snackbarHostState.showSnackbar("Не удалось сохранить подборку.") }
                    }
                }
            },
        )
    }

    if (showPlaylistPicker && trackToAddToPlaylist != null) {
        val selectedTrack = library.tracks.firstOrNull { it.id == trackToAddToPlaylist }
        PlaylistPickerDialog(
            playlists = library.playlists,
            trackTitle = selectedTrack?.displayTitle ?: "этот трек",
            onDismiss = { showPlaylistPicker = false; trackToAddToPlaylist = null },
            onCreateNew = {
                if (!restoreActionGate.isBlocked) {
                    showPlaylistPicker = false
                    showCreatePlaylist = true
                }
            },
            onSelect = { playlist ->
                if (!restoreActionGate.isBlocked) {
                    showPlaylistPicker = false
                    val trackId = trackToAddToPlaylist
                    trackToAddToPlaylist = null
                    scope.launch {
                        val selectedId = trackId ?: return@launch
                        runCatching {
                            restoreActionGate.withLibraryAction {
                                withContext(Dispatchers.IO) { repository.addToPlaylist(playlist.id, selectedId) }
                            }
                        }
                            .onSuccess { updated ->
                                if (updated != null) {
                                    library = updated
                                    snackbarHostState.showSnackbar("Добавлено в подборку «${playlist.name}».")
                                }
                            }
                            .onFailure { snackbarHostState.showSnackbar("Не удалось изменить подборку.") }
                    }
                }
            },
        )
    }

    trackToRemove?.let { track ->
        ConfirmRemoveDialog(
            track = track,
            onDismiss = { trackToRemove = null },
            onConfirm = {
                if (!restoreActionGate.isBlocked) {
                    trackToRemove = null
                    scope.launch {
                        val result = runCatching {
                            restoreActionGate.withLibraryAction {
                                controller?.let { player ->
                                    for (index in player.mediaItemCount - 1 downTo 0) {
                                        if (player.getMediaItemAt(index).mediaId == track.id) player.removeMediaItem(index)
                                    }
                                }
                                withContext(Dispatchers.IO) { repository.removeTrack(track.id) }
                            }
                        }
                        result.onSuccess { updated ->
                            if (updated != null) {
                                library = updated
                                snackbarHostState.showSnackbar("Копия удалена с телефона.")
                            }
                        }
                            .onFailure { snackbarHostState.showSnackbar("Не удалось удалить трек.") }
                    }
                }
            },
        )
    }

    playlistToDelete?.let { playlist ->
        ConfirmDeletePlaylistDialog(
            playlist = playlist,
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                if (!restoreActionGate.isBlocked) {
                    playlistToDelete = null
                    scope.launch {
                        runCatching {
                            restoreActionGate.withLibraryAction {
                                withContext(Dispatchers.IO) { repository.removePlaylist(playlist.id) }
                            }
                        }
                            .onSuccess { updated ->
                                if (updated != null) {
                                    library = updated
                                    if (openCollectionId == playlist.id) openCollectionId = null
                                    snackbarHostState.showSnackbar("Подборка удалена. Музыка на телефоне сохранена.")
                                }
                            }
                            .onFailure { snackbarHostState.showSnackbar("Не удалось удалить подборку.") }
                    }
                }
            },
        )
    }
}

private class BackupCancellationToken {
    private val operationGate = BackupOperationGate()
    val progressThrottle = BackupProgressThrottle()

    fun requestCancellation(): Boolean = operationGate.requestCancellation()
    fun beginFinalizing(): Boolean = operationGate.beginFinalizing()
    fun checkCancelled() = operationGate.checkCancelled()
}

private fun formatBackupProgress(label: String, progress: BackupProgress): String {
    val completed = formatBackupBytes(progress.completedBytes)
    val total = progress.totalBytes
    return if (total == null || total <= 0) "$label: $completed" else "$label: $completed из ${formatBackupBytes(total)}"
}

private fun backupFailureMessage(error: Throwable, restoring: Boolean): String {
    if (error is CancellationException) {
        return if (restoring) "Операция отменена. Текущая медиатека не изменена." else "Операция отменена. Выбранный файл может быть неполным."
    }
    val details = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if ("free space" in details || "not enough space" in details) {
        return "Недостаточно свободного места. Текущая медиатека не изменена."
    }
    return if (restoring) {
        "Не удалось проверить или восстановить архив. Сохранённые аудиокопии не удаляй; состояние медиатеки перепроверено."
    } else {
        "Не удалось создать ZIP-копию. Выбранный файл может быть неполным; проверь его перед использованием."
    }
}
