package com.neiro.aftertone

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.neiro.aftertone.storage.LocalLibraryRepository
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
import com.neiro.aftertone.ui.theme.AftertoneBackground
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertonePalette
import com.neiro.aftertone.ui.theme.AftertoneText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    val repository = remember(context) { LocalLibraryRepository(context) }
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

    LaunchedEffect(repository) {
        try {
            library = withContext(Dispatchers.IO) { repository.load() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            libraryLoadError = true
        }
        isLibraryLoaded = true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            isImporting = true
            var importedCount = 0
            var failedCount = 0
            uris.forEach { uri ->
                val result = runCatching { withContext(Dispatchers.IO) { repository.import(uri) } }
                if (result.isSuccess) importedCount++ else failedCount++
            }
            library = withContext(Dispatchers.IO) { repository.load() }
            isImporting = false
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
        if (!isImporting && !libraryLoadError) importLauncher.launch(arrayOf("audio/*"))
    }

    fun playTracks(tracks: List<Track>, selectedTrackId: String) {
        val player = controller
        if (player == null || tracks.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Проигрыватель подключается. Попробуй ещё раз через секунду.") }
            return
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

    fun toggleFavorite(trackId: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.toggleFavorite(trackId) } }
                .onSuccess { library = it }
                .onFailure { snackbarHostState.showSnackbar("Не удалось изменить избранное.") }
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.removeFromPlaylist(playlistId, trackId) } }
                .onSuccess { library = it; snackbarHostState.showSnackbar("Трек убран из подборки.") }
                .onFailure { snackbarHostState.showSnackbar("Не удалось изменить подборку.") }
        }
    }

    fun addToCollection(trackId: String) {
        trackToAddToPlaylist = trackId
        showPlaylistPicker = library.playlists.isNotEmpty()
        if (library.playlists.isEmpty()) showCreatePlaylist = true
    }

    fun requestRemove(track: Track) {
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
                    onTabSelected = { tab -> activeTab = tab; openCollectionId = null },
                    onOpenNowPlaying = { showNowPlaying = true },
                    onPlayPause = { if (controller?.isPlaying == true) controller.pause() else controller?.play() },
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
                        "Сохранённый список не изменён, а аудиофайлы не удалены. Изменения отключены, чтобы не потерять данные. Не удаляй Aftertone.",
                        color = AftertoneMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                            onTogglePlayPause = { if (controller?.isPlaying == true) controller.pause() else controller?.play() },
                            onToggleShuffle = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                            onToggleRepeat = { controller?.let { it.repeatMode = nextRepeatMode(it.repeatMode) } },
                            onPrevious = { if (controller?.hasPreviousMediaItem() == true) controller.seekToPreviousMediaItem() },
                            onNext = { if (controller?.hasNextMediaItem() == true) controller.seekToNextMediaItem() },
                            onSeek = { position -> controller?.seekTo(position) },
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
                                onCreatePlaylist = { trackToAddToPlaylist = null; showCreatePlaylist = true },
                                onRequestDeletePlaylist = { playlistToDelete = it },
                            )
                            AppTab.SETTINGS -> SettingsScreen(
                                selectedPalette = selectedPalette,
                                onPaletteSelected = onPaletteSelected,
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
                showCreatePlaylist = false
                scope.launch {
                    val oldIds = library.playlists.mapTo(hashSetOf(), Playlist::id)
                    val result = runCatching { withContext(Dispatchers.IO) { repository.addPlaylist(name) } }
                    result.onSuccess { updated ->
                        val created = updated.playlists.firstOrNull { it.id !in oldIds }
                        library = updated
                        if (created != null && trackToAddToPlaylist != null) {
                            val trackId = trackToAddToPlaylist
                            library = withContext(Dispatchers.IO) {
                                repository.addToPlaylist(created.id, trackId ?: return@withContext library)
                            }
                            trackToAddToPlaylist = null
                            snackbarHostState.showSnackbar("Подборка создана, трек добавлен.")
                        } else if (created != null) {
                            snackbarHostState.showSnackbar("Подборка создана.")
                        } else {
                            snackbarHostState.showSnackbar("Подборка с таким названием уже есть.")
                        }
                    }.onFailure { snackbarHostState.showSnackbar("Не удалось сохранить подборку.") }
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
            onCreateNew = { showPlaylistPicker = false; showCreatePlaylist = true },
            onSelect = { playlist ->
                showPlaylistPicker = false
                val trackId = trackToAddToPlaylist
                trackToAddToPlaylist = null
                scope.launch {
                    val selectedId = trackId ?: return@launch
                    runCatching { withContext(Dispatchers.IO) { repository.addToPlaylist(playlist.id, selectedId) } }
                        .onSuccess { library = it; snackbarHostState.showSnackbar("Добавлено в подборку «${playlist.name}».") }
                        .onFailure { snackbarHostState.showSnackbar("Не удалось изменить подборку.") }
                }
            },
        )
    }

    trackToRemove?.let { track ->
        ConfirmRemoveDialog(
            track = track,
            onDismiss = { trackToRemove = null },
            onConfirm = {
                trackToRemove = null
                scope.launch {
                    controller?.let { player ->
                        for (index in player.mediaItemCount - 1 downTo 0) {
                            if (player.getMediaItemAt(index).mediaId == track.id) player.removeMediaItem(index)
                        }
                    }
                    runCatching { withContext(Dispatchers.IO) { repository.removeTrack(track.id) } }
                        .onSuccess { library = it; snackbarHostState.showSnackbar("Копия удалена с телефона.") }
                        .onFailure { snackbarHostState.showSnackbar("Не удалось удалить трек.") }
                }
            },
        )
    }

    playlistToDelete?.let { playlist ->
        ConfirmDeletePlaylistDialog(
            playlist = playlist,
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                playlistToDelete = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { repository.removePlaylist(playlist.id) } }
                        .onSuccess {
                            library = it
                            if (openCollectionId == playlist.id) openCollectionId = null
                            snackbarHostState.showSnackbar("Подборка удалена. Музыка на телефоне сохранена.")
                        }
                        .onFailure { snackbarHostState.showSnackbar("Не удалось удалить подборку.") }
                }
            },
        )
    }
}
