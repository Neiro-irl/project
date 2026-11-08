package com.neiro.aftertone.playback

import android.content.Intent
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.ExoPlayer
import com.neiro.aftertone.storage.LocalLibraryRepository
import com.neiro.aftertone.storage.RestoreActionGate

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val restoreActionGate: RestoreActionGate by lazy {
        LocalLibraryRepository.shared(this).restoreActionGate
    }
    private val trustedControllersOnly = object : MediaSession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!controller.isTrusted) return MediaSession.ConnectionResult.reject()
            return super<MediaSession.Callback>.onConnect(session, controller)
        }

        @UnstableApi
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (!allowsRestoreCommand(controller)) return SessionResult.RESULT_ERROR_PERMISSION_DENIED
            return super<MediaSession.Callback>.onPlayerCommandRequest(session, controller, playerCommand)
        }

        @UnstableApi
        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (!allowsRestoreCommand(controller)) return false
            return super<MediaSession.Callback>.onMediaButtonEvent(session, controller, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(trustedControllersOnly)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    @UnstableApi
    private fun allowsRestoreCommand(controller: MediaSession.ControllerInfo): Boolean =
        restoreActionGate.allowsMediaSessionCommand(
            isTrusted = controller.isTrusted,
            isAppController = controller.uid == Process.myUid() && controller.packageName == packageName,
        )
}
